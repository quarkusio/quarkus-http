package io.undertow.websockets;

import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;

import javax.websocket.Extension;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Extensions {

    public static final Extension PERMESSAGE_DEFLATE = new ExtensionImpl(new WebSocketExtensionData("permessage-deflate", Collections.emptyMap()));
    public static final Extension DEFLATE_FRAME = new ExtensionImpl(new WebSocketExtensionData("deflate-frame", Collections.emptyMap()));

    public static final List<Extension> EXTENSIONS = Collections.unmodifiableList(Arrays.asList(PERMESSAGE_DEFLATE, DEFLATE_FRAME));

}
