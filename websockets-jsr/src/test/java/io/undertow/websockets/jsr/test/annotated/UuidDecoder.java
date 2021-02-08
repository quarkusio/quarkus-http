package io.undertow.websockets.jsr.test.annotated;

import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import java.util.UUID;

public class UuidDecoder implements Decoder.Text<UUID> {

    @Override
    public void init(EndpointConfig config) {

    }

    @Override
    public void destroy() {

    }

    @Override
    public UUID decode(String s) {
        return UUID.fromString(s);
    }

    @Override
    public boolean willDecode(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}