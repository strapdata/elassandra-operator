package com.strapdata.cassandra.driver;

import com.datastax.driver.core.policies.AddressTranslator;
import com.fasterxml.jackson.annotation.JsonTypeName;

import systems.composable.dropwizard.cassandra.network.AddressTranslatorFactory;

/**
 * A factory for configuring and building
 * {@link AddressTranslator} instances.
 */
@JsonTypeName("kubernetesDnsTranslator")
public class KubernetesDnsAddressTranslatorFactory implements AddressTranslatorFactory {
    @Override
    public AddressTranslator build() {
        return new KubernetesDnsAddressTranslator();
    }
}
