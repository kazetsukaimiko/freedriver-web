/**
 * The agent dispatcher. Its classes are primary adapters, the HTTP resources, MCP tools and
 * filters that take a caller's request, unless a class carries a role of its own: the job store
 * port and its exception, the application core ({@link io.freedriver.dispatcher.JobStoreService}),
 * the Mongo repositories and configuration it uses, and the OpenBot webhook that calls out.
 */
@PrimaryAdapter
package io.freedriver.dispatcher;

import org.jmolecules.architecture.hexagonal.PrimaryAdapter;
