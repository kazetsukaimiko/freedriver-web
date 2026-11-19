/**
 * The dispatcher root. Its role is the unqualified port, for {@link DispatcherConfig}, a
 * {@code @ConfigMapping} both sides read. The other classes stay here and carry their own role:
 * {@link DispatcherException} is a primary port, {@link WebhookRefused} is the webhook failure a
 * secondary adapter throws ({@code ArchitectureTest.exceptionPackage()} names this package),
 * {@link HttpsWebhookAddress} is the secondary port on the webhook address, and {@link JobIndexes}
 * is the secondary adapter that creates indexes. A class carries a role only when it differs from
 * this package.
 */
@Port
package io.freedriver.dispatcher;

import org.jmolecules.architecture.hexagonal.Port;
