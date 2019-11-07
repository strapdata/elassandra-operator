Elassandra Operator
===================

Elassandra Operator is a Kubernetes operator for Elassandra.

Elassandra Operator features
----------------------------

* Manage a Kuberentes statefulset of Elassandra nodes per cloud-provider zone to ensure high availability.
* Manage Cassandra seeds when datacenters are deployed on several Kuberenetes clusters.
* Generates SSL/TLS certificates for Elassandra nodes
* Manage backups and restores from/to your blobstore (S3, Azure, GCS)
* Automatically adjust the Cassandra replication factor for managed Keyspaces
* Update Cassandra passwords when a Kubernetes secret change.
* Create Cassandra roles and automatically grants the desired permissions on keyspaces.
* Mange Cassandra cleanup after a scale-up
* Allow to disable/enable Elasticsearch search on a node
* Copy Cassandra GC logs and Heapdump on your blogbstore for analysis
* Monitor the Elassandra JVM health and crash-it
* Efficiently expose Elassandra metrics for the prometheus-operator
* Deploy Cassandra reaper to ensure continuous Cassandra repairs.
* Deploy multiple Kibana instances with a dedicated index in Elassandra.

Architecture
------------

High Availibility
.................

Elassandra high availibilty across cloud-provider zones is achieve by using one StatfulSet per Cassandra rack.


Getting started with helm
-------------------------

The Elassandra Operator is available under two helm charts, the first one install the operator and underlying resources (CRD, ServiceAccounts, Services...).
The second one is used to define the configuration of your Elassandra Datacenter thanks to a CustomResourceDefinition.

Firstly, deploy an operator POD in your kubrenetes cluster.

.. code-block:: bash

    helm install --namespace default --name myproject -f custom-values.yaml elassandra-operator

Check the pod is up and running.

.. code-block:: bash

      kubectl --namespace default get pods -l "app=elassandra-operator,release=myproject"

Finally, deploy the elassandra-datacenter CRD definition.

.. code-block:: bash

    helm install --namespace default --name mycluster-mydatacenter -f custom-values.yaml elassandra-datacenter

After a short period, you should have some elassandra running pods. You can list them using "elassandra" as value for the "app" label.

.. code-block:: bash

    kubectl --namespace default get pods -l "app=elassandra"


Getting started with StrapCloud
-------------------------------

.. note:: Strapcloud only supports Google Chrome browser.

Signing Up
..........

Create your strapcloud account on `https://console.strapdata.com/cloudmanager/signup.jsp <https://console.strapdata.com/cloudmanager/signup.jsp>`_ to start a free Elassandra Enterprise trial :

* Enter the required information, including your corporate email address, your phone number and your voucher code.
* Click the **Sign up** button.
* Check your email inbox and open the Strapcloud welcome message, then click on the provided link to confirm your registration.

.. image:: images/strapcloud-signup.png
   :scale: 60%

You can then now log in to the strapcloud console and deploy your Elassandra Enterprise cluster.

Deploy your Elassandra cluster
..............................

.. note:: Free trial offer comes with a limited pre-provisionned cluster and does not allow to create new clusters or datacenters.

Before deploying your datacenters, configure your SSH keys and network access restriction:

* Choose you cluster in the cluster drop-down selection.
* Click on the **Edit** button to open the cluster setting dialog box.
* In the cluster tab, add your SSH keys, and autorized IP addresses. SSH keys and authorized IP addresses can be later updated when deploying a datacenter.
* Click on the **Update** button to save your settings.

.. image:: images/strapcloud-cluster-settings.png
   :scale: 30%

You can now configure your datacenters network access restriction:

* Select *DC1* in the datcenter drop-down selection.
* Click on the **Edit** button to open the datacenter setting dialog box.
* In the Cassandra, Elasticsearch and Docker tabs, configure allowed IP addresses to restict access to your datacenter. For trial clusters only, CQL (9042/tcp), Elasticsearch (9200/tcp) and HTTPS (443/tcp) are allowed from any source by default.
* Click on the **Update** button to save your settings.

.. image:: images/strapcloud-datacenter-settings.png
   :scale: 50%
   
In order to start the deployment :

* On the datacenter dropdown-menu, click on **Deploy**.
* Set the number of nodes to 3.
* Unless you already have data to replicate from another datacenter, leave **Keyspaces** empty and **Auto-boostrap** checked.
* Click on the **Deploy** button to start the deployment.

.. image:: images/strapcloud-datacenter-deploy.png
   :scale: 40%
   
You can now follow the deployment progress bar, it should take 10-15 minutes to complete.

.. image:: images/strapcloud-datacenter-deploy-progress.png

Once the first datacenter is deployed, you will be able to deploy the second one :

* If you want to replicate data on DC1 from a user keyspace, enter the keyspaces name and the associated replication factor in the **Keyspaces**. 
* Unckeck the **Auto-bootstrap**, nodes will start with ``auto_bootstrap: false`` and then `rebuild <http://cassandra.apache.org/doc/latest/tools/nodetool/rebuild.html>`_ from an exiting datacenter.
* Click on the **Deploy** button to start the deployment.

Connecting to a cluster
.......................

Depending on your network acces restrictions, you will be able to connect to :

* Server over the SSH protocol (22/tcp) with the registered SSH keys. 
* Cassandra over the CQL binary protocol on port 9042/tcp.
* Elasticsearch HTTPS protocol port 9200/tcp.
* Kibana, Grafana, Cassandra-Reaper and ELAdmin services over the HTTPS protocol 443/tcp.

All theses connections are SSL encrypted, and server certificates are issued by our internal certificate authority. You should import and trust this internal CA from the cluster tab as shown bellow.

.. image:: images/strapcloud-cluster-info.png

When connected to nodes with **centos** or **root** account, security settings are automatically configured in :

* $HOME/.cassandra/cqlshrc
* $HOME/.cassandra/nodetool-ssl.properties
* $HOME/.curlrc

Then you can use pre-defined bash aliases to run **nodetool**, **cqlsh** or the following elasticsearch shortcuts :

.. cssclass:: table-bordered

+----------+---------------------------------------+
| Alias    | Description                           |
+==========+=======================================+
| state    | Show the Elasticsearch cluster state. |
+----------+---------------------------------------+
| indices  | List Elasticsearch indices            |
+----------+---------------------------------------+
| segments | List elasticsearch segments           |
+----------+---------------------------------------+
| shard    | List Elasticsearch shards.            |
+----------+---------------------------------------+
| nodes    | List Elasticsearch nodes information. |
+----------+---------------------------------------+

Access to datacenter services are also available from the datacenter tab as shown bellow :
 
.. image:: images/strapcloud-datacenter-info.png

Monitoring
----------

A Grafana dashboard is available for each strapcloud datacenter :

* In the datacenter tab, click on the **Grafana** button.
* Log in with the **admin** password available in the Cassandra tab of the cluster settings dialog box.

.. image:: images/strapcloud-grafana-elassandra-jmx.png

Continous Cassandra repair
--------------------------

In order to ensure data consistency, a continuous cassandra repair may be scheduled by a `Cassandra Reaper <https://http://cassandra-reaper.io/>`_ deamon running on each datacenter:

* In the datacenter tab, click on the **Reaper** button.
* Log in with the **admin** account, the admin password is available in the Cassandra tab of the cluster settings dialog box.

.. image:: images/strapcloud-cassandra-reaper.png

Cassandra Data Import
---------------------

As soon as you have a correct CQL schema, you can import CSV file into your cluster through a COPY FROM command. The following sample illustrate the import process:

* Open a cqlsh session and create a Cassandra keyspace:

.. code::

   admin@cqlsh> CREATE KEYSPACE iot WITH replication = {'class': 'NetworkTopologyStrategy','DC1':'1'};

* Create a cassandra table matching your CSV data types:

.. code::

   admin@cqlsh> CREATE TABLE iot.timeserie ( 
    device_id uuid, 
    device_name text static, 
    vesid int, 
    device_type text, 
    param_name text, 
    ts timestamp, 
    flags text, 
    unit text, 
    value double, 
    avg double, 
    speed double, 
    reference double, 
    filler text,
    PRIMATY KEY ((device_id,param_name),ts)
    );

* Create the associated Elasticsearch index by discovering the CQL schema:

.. code::

   $ curl -XPUT "https://$NODE:9200/iot" -d '{ 
        "settings": { "keyspace":"iot", "index.search_strategy_class":"RandomSearchStrategy" },
        "mappings": {
            "timeserie": { 
               "discover" : ".*", 
               "_meta": { "index_static_columns":true }
            }
        }
     }'
   {"acknowledged":true,"shards_acknowledged":true}

* Load your CSV file at a limited rate depending on your resources:

.. code::

   admin@cqlsh> COPY iot.timeserie (device_id,device_name,vesid,device_type,param_name,ts,flags,unit,value,avg,speed,reference,filler) FROM '/tmp/histo-2018.csv' WITH DELIMITER=';' AND header=true AND DATETIMEFORMAT='%m/%d/%Y %H:%M:%S' AND NULL=null AND INGESTRATE=2500;
   Reading options from the command line: {'datetimeformat': '%m/%d/%Y %H:%M:%S', 'header': 'true', 'delimiter': ';', 'null': 'null', 'ingestrate': '2500'}
   Using 1 child processes
   
   Starting copy of iot.timeserie with columns [device_id, device_name, vesid, device_type, param_name, ts, flags, unit, value, avg, speed, reference, filler].
   Processed: 279319 rows; Rate:    1498 rows/s; Avg. rate:    2507 rows/s
   279319 rows imported from 1 files in 1 minute and 51.420 seconds (0 skipped).

* Check your index size (*indices* is an alias to the elasticsearch API).

.. code::

   $ indices
   health status index   uuid                   pri rep docs.count docs.deleted store.size pri.store.size
   green  open   iot     m6yJddOPRRC0C0Xuq4u49g   3   0     279318            0     23.6mb         23.6mb
   green  open   .kibana HfZbXeMWTNuHsBDfVf946Q   3   2          3            0      9.9kb          9.9kb

Kibana
------

Visualize your data in Elasticsearch with Kibana :

* In the datacenter tab, click on the **Kibana** button.
* Log in with the **kibana** account, the kibana password is available in the Elasticsearch tab of the cluster settings dialog box.

In order to visualize your data, you must grant the *SELECT* permission to the *kibana* role as shown bellow for our sample data:

.. code::

   GRANT SELECT ON KEYSPACE iot TO kibana;

Then, you will be able to graph data from the *iot* index.

.. image:: images/strapcloud-kibana-iot.png

ElAdmin
-------

Strapcloud comes with a simple CQL explorer **eladmin** allowing to view and change Cassandra table content :

* In the datacenter tab, click on the **ElAdmin** button.
* Log in with the **admin** account, the admin password is available in the Cassandra tab of the cluster settings dialog box.

.. image:: images/strapcloud-eladmin-iot.png

Apache Spark
------------

If the Apache Spark service is enabled, you can connect over SSH to a node, switch to the *spark* linux user, and submit a spark job or open a spark shell by launching the pre-configured *myshell.sh*:

.. code::

   $ sudo su - spark
   $ cd /opt/spark-2.1.1-bin-hadoop2.7/
   $ ./myshell.sh
   ...
   Spark context Web UI available at http://54.38.40.142:4040
   Spark context available as 'sc' (master = spark://10.16.0.2:7077, app id = app-20180323001741-0002).
   Spark session available as 'spark'.
   Welcome to
         ____              __
        / __/__  ___ _____/ /__
       _\ \/ _ \/ _ `/ __/  '_/
      /___/ .__/\_,_/_/ /_/\_\   version 2.1.1
         /_/
            
   Using Scala version 2.11.8 (Java HotSpot(TM) 64-Bit Server VM, Java 1.8.0_162)
   Type in expressions to have them evaluated.
   Type :help for more information.
   
   scala> 

In order to access a user keyspace, you should grant the *SELECT* permission to the role *spark*:

.. code :

   admin@cqlsh> GRANT SELECT ON KEYSPACE iot TO spark;
   
Then you will be able to read your data from spark as follow:

.. code::

   import com.datastax.spark.connector._
   import org.apache.spark.sql.cassandra._
   import org.apache.spark.{SparkConf, SparkContext}
   
   scala>val cf = spark.read.format("org.apache.spark.sql.cassandra").options(Map( "table" -> "timeserie", "keyspace" -> "iot")).load()
   cf: org.apache.spark.sql.DataFrame = [device_id: string, param_name: string ... 11 more fields]
   
   scala> cf.show(10);
   +--------------------+------------------+--------------------+----+--------------------+-----------+------+------+---------+-----+----+----------+-----+
   |           device_id|        param_name|                  ts| avg|         device_name|device_type|filler| flags|reference|speed|unit|     value|vesid|
   +--------------------+------------------+--------------------+----+--------------------+-----------+------+------+---------+-----+----+----------+-----+
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 09:37:...|null|X477300EE-model1 ...|    History|      |196610|     null| null|m/s²|0.00484548|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 09:50:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00622862|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 10:08:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00552573|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 10:16:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00561744|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 10:29:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00546834|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 10:41:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²| 0.0056335|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 11:00:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00539362|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 11:13:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00573572|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 11:26:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00562438|    3|
   |b91f0cd5-936c-46f...|SE03_Rlt NU328E_03|2017-06-28 11:40:...|null|X477300EE-model1 ...|    History|      |     2|     null| null|m/s²|0.00534293|    3|
   +--------------------+------------------+--------------------+----+--------------------+-----------+------+------+---------+-----+----+----------+-----+
   only showing top 10 rows

The Spark Web UI is available on the standard port 4040 to monitor and inspect job execution in a web browser. 
If you need to run more than spark application (SparkContext), please contact the strapdata support to open additional ports.

.. image:: images/strapcloud-spark-driver-ui.png

