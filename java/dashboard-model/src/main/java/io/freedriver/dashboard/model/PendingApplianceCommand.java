package io.freedriver.dashboard.model;

import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.CanonicalUuid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * One pending delayed command returned by the scheduled list.
 * JSON names are {@code applianceName} and {@code on}. {@code on} is a JSON boolean.
 * {@code dueAt} is an ISO-8601 string. Strict tokens follow {@link ApplianceCommandEntryRequest}.
 */
public record PendingApplianceCommand(
        @NotBlank @CanonicalUuid String id,
        @NotBlank @Size(min = 1, max = ApplianceSchemas.NAME_MAX) String applianceName,
        @NotNull Boolean on,
        @NotNull Instant dueAt) {}
