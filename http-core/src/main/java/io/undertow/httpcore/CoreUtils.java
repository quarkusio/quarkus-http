package io.undertow.httpcore;

class CoreUtils {

    public static String formatPossibleIpv6Address(String address) {
        if (address == null) {
            return null;
        }
        if (!address.contains(":")) {
            return address;
        }
        if (address.startsWith("[") && address.endsWith("]")) {
            return address;
        }
        return "[" + address + "]";
    }

}
