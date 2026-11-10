package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

const httpRequestTimeout = 30 * time.Second
const maxHTTPContentBytes int64 = 32 << 20
const maxHTTPDownloadBytes int64 = 256 << 20

var errFailConnectSocks5 = errors.New("fail connect socks5")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Timeout = httpRequestTimeout
	client.h1h2Transport.DialContext = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	client.h1h2Transport.TLSHandshakeTimeout = 10 * time.Second
	client.h1h2Transport.ResponseHeaderTimeout = 15 * time.Second
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			deadline := time.Now().Add(10 * time.Second)
			if parentDeadline, ok := ctx.Deadline(); ok && parentDeadline.Before(deadline) {
				deadline = parentDeadline
			}
			_ = socksConn.SetDeadline(deadline)
			stopCancel := context.AfterFunc(ctx, func() { _ = socksConn.Close() })
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			stopCancel()
			if err != nil {
				_ = socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			if ctx.Err() != nil {
				_ = socksConn.Close()
				return nil, ctx.Err()
			}
			_ = socksConn.SetDeadline(time.Time{})
			return socksConn, nil
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	data := bytes.Clone(content)
	r.request.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(data)), nil }
	r.request.Body, _ = r.request.GetBody()
	r.request.ContentLength = int64(len(data))
}

func (r *httpRequest) SetContentString(content string) { r.SetContent([]byte(content)) }

func (r *httpRequest) Execute() (response HTTPResponse, err error) {
	defer device.DeferPanicToError("http execute", func(e error) { response = nil; err = e })
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	raw, err := r.h1h2Client.Do(&r.request)
	if err != nil {
		if raw != nil && raw.Body != nil {
			raw.Body.Close()
		}
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	if raw.StatusCode != http.StatusOK {
		raw.Body.Close()
		return nil, fmt.Errorf("HTTP %s", raw.Status)
	}
	return &httpResponse{Response: raw}, nil
}

type requestFunc func(context.Context) (*http.Response, error)

// An unbuffered hand-off transfers ownership of exactly one response body.
// Every losing request, including one that finishes after timeout, closes its body.
func raceHTTP(parent context.Context, timeout time.Duration, requests ...requestFunc) (HTTPResponse, error) {
	ctx, cancel := context.WithTimeout(parent, timeout)
	type result struct {
		response *http.Response
		err      error
		index    int
	}
	results := make(chan result)
	cancels := make([]context.CancelFunc, len(requests))
	for i, request := range requests {
		child, childCancel := context.WithCancel(ctx)
		cancels[i] = childCancel
		go func(index int, request requestFunc) {
			response, err := func() (response *http.Response, err error) {
				defer device.DeferPanicToError("http request", func(e error) { err = e })
				return request(child)
			}()
			if err == nil && response == nil {
				err = errors.New("empty HTTP response")
			}
			if err == nil && response.StatusCode != http.StatusOK {
				err = fmt.Errorf("HTTP %s", response.Status)
			}
			if err != nil && response != nil {
				response.Body.Close()
				response = nil
			}
			select {
			case results <- result{response, err, index}:
			case <-child.Done():
				if response != nil {
					response.Body.Close()
				}
			}
		}(i, request)
	}
	var failures error
	for range requests {
		select {
		case received := <-results:
			if received.err != nil {
				failures = errors.Join(failures, received.err)
				continue
			}
			for i, stop := range cancels {
				if i != received.index {
					stop()
				}
			}
			received.response.Body = &cleanupBody{ReadCloser: received.response.Body, cleanup: cancel}
			return &httpResponse{Response: received.response}, nil
		case <-ctx.Done():
			cancel()
			return nil, errors.Join(ctx.Err(), failures)
		}
	}
	cancel()
	return nil, failures
}

type cleanupBody struct {
	io.ReadCloser
	once    sync.Once
	cleanup func()
}

func (b *cleanupBody) Close() error {
	err := b.ReadCloser.Close()
	b.once.Do(b.cleanup)
	return err
}

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	clone := func(ctx context.Context) (*http.Request, error) {
		request := r.request.Clone(ctx)
		if r.request.GetBody != nil {
			body, err := r.request.GetBody()
			if err != nil {
				return nil, err
			}
			request.Body = body
		}
		return request, nil
	}
	requests := []requestFunc{func(ctx context.Context) (*http.Response, error) {
		request, err := clone(ctx)
		if err != nil {
			return nil, err
		}
		transport := &http.Transport{
			DisableKeepAlives:     true,
			DialContext:           (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
			TLSHandshakeTimeout:   10 * time.Second,
			ResponseHeaderTimeout: 15 * time.Second,
			DialTLSContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				conn, err := (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, address)
				if err != nil {
					return nil, err
				}
				host, _, err := net.SplitHostPort(address)
				if err != nil {
					conn.Close()
					return nil, err
				}
				client, err := ech.NewECHClientConfig(host, &r.tls, gLocalDNSTransport).Client(ctx, conn)
				if err == nil {
					err = client.HandshakeContext(ctx)
				}
				if err != nil {
					conn.Close()
					return nil, err
				}
				return client, nil
			},
		}
		response, err := (&http.Client{Transport: transport}).Do(request)
		if err != nil {
			transport.CloseIdleConnections()
			return response, err
		}
		response.Body = &cleanupBody{ReadCloser: response.Body, cleanup: transport.CloseIdleConnections}
		return response, nil
	}}
	if r.request.URL.Scheme == "https" {
		requests = append(requests, func(ctx context.Context) (*http.Response, error) {
			request, err := clone(ctx)
			if err != nil {
				return nil, err
			}
			transport := &http3.Transport{TLSClientConfig: r.tls.Clone(), QUICConfig: &quic.Config{MaxIdleTimeout: 10 * time.Second}}
			response, err := (&http.Client{Transport: transport}).Do(request)
			if err != nil {
				transport.Close()
				return response, err
			}
			response.Body = &cleanupBody{ReadCloser: response.Body, cleanup: func() { transport.Close() }}
			return response, nil
		})
	}
	return raceHTTP(r.request.Context(), httpRequestTimeout, requests...)
}

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxHTTPContentBytes+1))
		if int64(len(h.content)) > maxHTTPContentBytes {
			h.content = nil
			h.contentError = fmt.Errorf("HTTP response exceeds %d MiB", maxHTTPContentBytes>>20)
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	size, err := io.Copy(file, io.LimitReader(h.Body, maxHTTPDownloadBytes+1))
	if size > maxHTTPDownloadBytes {
		err = errors.New("HTTP download exceeds 256 MiB")
	}
	if err != nil {
		file.Close()
		os.Remove(path)
	}
	return err
}
