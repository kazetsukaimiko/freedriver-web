package io.freedriver.keycloak.registration;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Resolves the host of a rootUrl or adminUrl. The policy passes {@code InetAddress::getAllByName}. */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
