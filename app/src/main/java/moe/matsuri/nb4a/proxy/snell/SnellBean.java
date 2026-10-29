package moe.matsuri.nb4a.proxy.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class SnellBean extends AbstractBean {

    public static final Creator<SnellBean> CREATOR = new CREATOR<SnellBean>() {
        @NonNull
        @Override
        public SnellBean newInstance() {
            return new SnellBean();
        }

        @Override
        public SnellBean[] newArray(int size) {
            return new SnellBean[size];
        }
    };

    // sing-box supports Snell v4 (obfs) and v6 (mode).
    public Integer version;
    public String psk;
    public String userKey;
    public Boolean reuse;
    // v4: none, http, tls
    public String obfsMode;
    public String obfsHost;
    // v6: default, unshaped, unsafe-raw
    public String mode;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (version == null) version = 4;
        if (psk == null) psk = "";
        if (userKey == null) userKey = "";
        if (reuse == null) reuse = false;
        if (obfsMode == null) obfsMode = "";
        if (obfsHost == null) obfsHost = "";
        if (mode == null) mode = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeInt(version);
        output.writeString(psk);
        output.writeString(userKey);
        output.writeBoolean(reuse);
        output.writeString(obfsMode);
        output.writeString(obfsHost);
        output.writeString(mode);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        this.version = input.readInt();
        psk = input.readString();
        userKey = input.readString();
        reuse = input.readBoolean();
        obfsMode = input.readString();
        obfsHost = input.readString();
        mode = input.readString();
    }

    @NotNull
    @Override
    public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }
}
