Compute resources
-----------------

Because it’s important that the containers have enough resources to actually run,
it is recommended to specify the CPU and memory requirements for all pods managed by the elassandra operator.

You can specify the resource limits for Elassandra pods, Kibana and Cassandra reaper pods by settings the following block:

* At the top level of the Elassandra datacenter spec for Elassandra pods,
* in the **elasticsearch.kibana.spaces** for each Kibana pod,
* in the **cassandra.reaper** for the Cassandra reaper pod.

.. code::

    resources:
      requests:
        cpu: 500m
        memory: 1Gi
      limits:
        cpu: 2000m
        memory: 2Gi

You can also specify the `priorityClassName <https://kubernetes.io/docs/concepts/configuration/pod-priority-preemption/>`_ for the managed pods to ensure Kubernetes properly runs your workload.
For more information about Kubernetes resource management, see `Managing Compute Resources for Containers <https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/>`_.