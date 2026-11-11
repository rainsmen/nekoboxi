package libcore

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type countedBody struct {
	io.Reader
	closed atomic.Bool
}

func (b *countedBody) Close() error { b.closed.Store(true); return nil }
func TestHTTPRaceCancelsTimeout(t *testing.T) {
	stopped := make(chan struct{})
	response, err := raceHTTP(context.Background(), 20*time.Millisecond, func(ctx context.Context) (*http.Response, error) {
		<-ctx.Done()
		close(stopped)
		return nil, ctx.Err()
	})
	if response != nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("%v %v", response, err)
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("request not canceled")
	}
}
func TestHTTPRaceKeepsWinnerAndClosesLateSuccess(t *testing.T) {
	winner := &countedBody{Reader: strings.NewReader("body")}
	loser := &countedBody{Reader: strings.NewReader("late")}
	release, done := make(chan struct{}), make(chan struct{})
	response, err := raceHTTP(context.Background(), time.Second,
		func(context.Context) (*http.Response, error) {
			return &http.Response{StatusCode: 200, Body: winner}, nil
		},
		func(ctx context.Context) (*http.Response, error) {
			<-release
			defer close(done)
			return &http.Response{StatusCode: 200, Body: loser}, nil
		},
	)
	if err != nil {
		t.Fatal(err)
	}
	close(release)
	<-done
	for deadline := time.Now().Add(time.Second); !loser.closed.Load() && time.Now().Before(deadline); {
		time.Sleep(time.Millisecond)
	}
	if !loser.closed.Load() {
		t.Fatal("late body leaked")
	}
	if winner.closed.Load() {
		t.Fatal("winner closed too early")
	}
	body, err := response.GetContent()
	if err != nil || string(body) != "body" || !winner.closed.Load() {
		t.Fatalf("%s %v", body, err)
	}
}
func TestHTTPBodyDeadlineAndLimit(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer server.Close()
	response, err := raceHTTP(context.Background(), 30*time.Millisecond, func(ctx context.Context) (*http.Response, error) {
		req, _ := http.NewRequestWithContext(ctx, "GET", server.URL, nil)
		return server.Client().Do(req)
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := response.GetContent(); err == nil {
		t.Fatal("unbounded body wait")
	}
	oversized := &countedBody{Reader: io.LimitReader(zeroReader{}, maxHTTPContentBytes+100)}
	h := &httpResponse{Response: &http.Response{Body: oversized}}
	if _, err := h.GetContent(); err == nil || !oversized.closed.Load() {
		t.Fatal("oversized body accepted or leaked")
	}
}

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) { clear(p); return len(p), nil }

func TestAssetDownloadDeadlineCoversBodyAndRemovesPartialFile(t *testing.T) {
	client := NewAssetHttpClient().(*httpClient)
	defer client.Close()
	if client.h1h2Client.Timeout != assetDownloadTimeout || assetDownloadTimeout <= httpRequestTimeout {
		t.Fatal("asset download must have a separate finite deadline")
	}
	if client.h1h2Transport.ResponseHeaderTimeout != 15*time.Second {
		t.Fatal("header timeout lost")
	}
	// Shorten the configured policy for this deadline/cleanup test.
	client.h1h2Client.Timeout = 100 * time.Millisecond
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.Write([]byte("partial database"))
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer server.Close()
	req := client.NewRequest()
	if err := req.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := req.Execute()
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "download.tmp")
	if err = response.WriteTo(path); err == nil {
		t.Fatal("unbounded body transfer")
	}
	if _, err = os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("partial download retained")
	}
}

func TestAssetDownloadWritesCompleteBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "complete database") }))
	defer server.Close()
	client := NewAssetHttpClient()
	defer client.Close()
	req := client.NewRequest()
	if err := req.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := req.Execute()
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "download.tmp")
	if err = response.WriteTo(path); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "complete database" {
		t.Fatalf("%q %v", data, err)
	}
}

func TestAbandonedAssetResponseCanBeClosedBeforeWriting(t *testing.T) {
	ended := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(ended)
	}))
	defer server.Close()
	client := NewAssetHttpClient()
	defer client.Close()
	request := client.NewRequest()
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	response.Close()
	select {
	case <-ended:
	case <-time.After(time.Second):
		t.Fatal("abandoned response still active")
	}
}
