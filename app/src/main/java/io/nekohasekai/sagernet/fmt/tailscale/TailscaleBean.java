package io.nekohasekai.sagernet.fmt.tailscale;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class TailscaleBean extends AbstractBean {

    public String authKey;
    public String controlUrl;
    public Boolean ephemeral;
    public String exitNode;
    public Boolean exitNodeAllowLanAccess;
    public Boolean acceptRoutes;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (authKey == null) authKey = "";
        if (controlUrl == null) controlUrl = "";
        if (ephemeral == null) ephemeral = false;
        if (exitNode == null) exitNode = "";
        if (exitNodeAllowLanAccess == null) exitNodeAllowLanAccess = false;
        if (acceptRoutes == null) acceptRoutes = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(authKey);
        output.writeString(controlUrl);
        output.writeBoolean(ephemeral);
        output.writeString(exitNode);
        output.writeBoolean(exitNodeAllowLanAccess);
        output.writeBoolean(acceptRoutes);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        authKey = input.readString();
        controlUrl = input.readString();
        ephemeral = input.readBoolean();
        exitNode = input.readString();
        exitNodeAllowLanAccess = input.readBoolean();
        acceptRoutes = input.readBoolean();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public TailscaleBean clone() {
        return KryoConverters.deserialize(new TailscaleBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TailscaleBean> CREATOR = new CREATOR<TailscaleBean>() {
        @NonNull
        @Override
        public TailscaleBean newInstance() {
            return new TailscaleBean();
        }

        @Override
        public TailscaleBean[] newArray(int size) {
            return new TailscaleBean[size];
        }
    };
}
