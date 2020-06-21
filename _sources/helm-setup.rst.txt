HELM setup
**********

Elassandra operator and datacenter can be easily installed using HELM 2 (HELM 3 is not currently supported).

.. _helm-setup:

HELM 2 install
==============

Install HELM 2 in your kubernetes cluster:

.. code::

    kubectl create serviceaccount --namespace kube-system tiller
    kubectl create clusterrolebinding tiller-cluster-rule --clusterrole=cluster-admin --serviceaccount=kube-system:tiller
    helm init --wait --service-account tiller

For more information, see the `HELM 2 website <https://v2.helm.sh/docs/install/>`_,
and apply `Best practices for securing HELM and Tiller <https://v2.helm.sh/docs/securing_installation/#best-practices-for-securing-helm-and-tiller>`_.

Add the Strapdata HELM repository
=================================

Add the Strapdata `HELM 2 repository <https://github.com/strapdata>`_

.. code::

    helm repo add strapdata https://charts.strapdata.com

In order to update the Strapdata HELM repository

.. code::

    helm repo update strapdata

.. tip::

    To quickly switch between Kubernetes contexts and namespaces, you can use `kubectx and kubens <https://github.com/ahmetb/kubectx>`_.