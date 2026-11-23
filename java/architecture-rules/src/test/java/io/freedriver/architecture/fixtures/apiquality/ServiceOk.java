package io.freedriver.architecture.fixtures.apiquality;

import jakarta.ws.rs.core.Response;

/** A service class whose dependencies sit on the allowlist. */
public class ServiceOk {
    @Note
    String label;

    Contract contract;
    Helper helper;
    Response response;
    javax.sql.DataSource dataSource;
    io.quarkus.arc.ArcContainer container;
    io.smallrye.config.SmallRyeConfig config;
    org.eclipse.microprofile.config.Config mp;
}
