package io.freedriver.app.instances;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class InstancesResource implements InstancesApi {

    private final HouseProvision houses;

    @Inject
    public InstancesResource(HouseProvision houses) {
        this.houses = houses;
    }

    @Override
    public InstanceList list() {
        return houses.list();
    }

    @Override
    public ProvisionedInstance create(CreateInstance request) {
        String label = request == null ? null : request.label();
        return houses.create(label);
    }

    @Override
    public ProvisionedInstance open(String instanceId) {
        return houses.open(instanceId);
    }
}
