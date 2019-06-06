#!/usr/bin/env groovy

def podLabel = "operator-${UUID.randomUUID().toString()}"

podTemplate(
        label: podLabel,
        name: podLabel,
        containers: [
                containerTemplate(
                        name: 'buildenv',
                        alwaysPullImage: true,
                        image: "docker.repo.strapdata.com/elassandra-operator/jenkins-buildenv:latest",
                        command: 'cat',
                        ttyEnabled: true,
                        workingDir: "/home/jenkins"
                ),
                containerTemplate(
                        name: 'azure',
                        alwaysPullImage: true,
                        image: "docker.repo.strapdata.com/elassandra-operator/jenkins-buildenv:latest",
                        command: 'cat',
                        ttyEnabled: true,
                        workingDir: "/home/jenkins"
                ),
        ],
        envVars: [
                envVar(key: 'JAVA_OPTS', value: '-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap'),
        ],
        volumes: [
                hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock')
        ],
        resourcelimitMemory: '3000Mi'
) {

  node(podLabel) {

    def tags = ""
    def aksCreated = false
    def aksCreating = false
    def aksName = "elassandra-operator-" + (Math.abs(new Random().nextInt() % 1000) + 1);

    withCredentials([
            usernamePassword(credentialsId: 'azure-ci-staging', usernameVariable: 'AZ_APP_ID', passwordVariable: 'AZ_PASSWORD'),
            string(credentialsId: 'azure-tenant-id', variable: 'AZ_TENANT_ID'),
            usernamePassword(credentialsId: 'ci-staging-aks', usernameVariable: 'AKS_SP_ID', passwordVariable: 'AKS_SP_PASSWORD'),
            usernamePassword(credentialsId: "nexus-jenkins-deployer", usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD'),
            usernamePassword(credentialsId: 'nexus-jenkins-deployer', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASSWORD')
    ]) {
      withEnv(["AKS_NAME=${aksName}", "SUFFIX=-staging", "PULL_SECRET=nexus-registry"]) {

        try {
          stage('init') {
            checkout scm
            sh 'printenv'
            tags = sh(returnStdout: true, script: "git tag --sort version:refname | tail -1").trim()

            container('buildenv') {
              sh 'mvn -version'
              sh 'kubectl version'
              sh 'docker --version'
              sh 'az --version'
              sh 'gcloud --version'
              sh 'java -version'
            }
          }

          stage("build") {
            parallel(
                    setup_aks: {
                      container('azure') {
                        sh "az login --service-principal -u ${AZ_APP_ID} -p ${AZ_PASSWORD} --tenant ${AZ_TENANT_ID}"
                        aksCreating = true
                        sh './strap-test/setup-aks'
                        aksCreated = true
                        aksCreating = false
                      }
                    },
                    build: {
                      container('buildenv') {
                        sh "docker login -u ${REGISTRY_USER} -p ${REGISTRY_PASSWORD} docker.repo.strapdata.com"
                        sh "make"
                        sh "make docker-release"
                      }
                    }
            )
          }

          stage('integration tests') {
            container('buildenv') {
              sh "az login --service-principal -u ${AZ_APP_ID} -p ${AZ_PASSWORD} --tenant ${AZ_TENANT_ID}"
              sh "./strap-test/aks/get-credentials"
              sh 'strap-test/test-scale dc1 1 3'
              sh 'strap-test/test-backup dc1 3'
            }
          }
        }
        catch (exc) {
          stage('print logs') {
            container('buildenv') {
              if (aksCreated) {
                sh './strap-test/lib/logs-operator'
                sh 'kubectl get all -o yaml'
                sh 'kubectl get crd -o yaml'
                sh 'kubectl get pvc -o yaml'
                sh 'kubectl get cm -o yaml'
                sh 'kubectl get cdc -o yaml'
              }
            }
          }
          throw exc
        }
        finally {
          stage('cleanup aks') {
            if (aksCreated || aksCreating) {
              container('buildenv') {
                sh './strap-test/cleanup-aks'
              }
            }
          }
        }
      }
    }
  }
}