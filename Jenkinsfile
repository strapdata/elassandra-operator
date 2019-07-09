#!/usr/bin/env groovy

def podLabel = "operator-${UUID.randomUUID().toString()}"

podTemplate(
        label: podLabel,
        name: podLabel,
        containers: [
                containerTemplate(
                        name: 'docker-tricks',
                        alwaysPullImage: true,
                        image: "bobrik/socat",
                        command: 'socat TCP-LISTEN:1234,fork UNIX-CONNECT:/var/run/docker.sock',
                        ttyEnabled: false,
                        workingDir: "/home/jenkins"
                ),
                containerTemplate(
                        name: 'buildenv',
                        alwaysPullImage: true,
                        image: "docker.repo.strapdata.com/strapkop/jenkins-buildenv:latest",
                        command: 'cat',
                        ttyEnabled: true,
                        workingDir: "/home/jenkins"
                ),
                containerTemplate(
                        name: 'azure',
                        alwaysPullImage: true,
                        image: "docker.repo.strapdata.com/strapkop/jenkins-buildenv:latest",
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
      withEnv(["AKS_NAME=${aksName}", "SUFFIX=-staging", "PULL_SECRET=nexus-registry", "DOCKER_HOST=tcp://localhost:1234"]) {

        try {
          stage('init') {
            checkout scm
            sh 'printenv'
            tags = sh(returnStdout: true, script: "git tag --sort version:refname | tail -1").trim()

            container('buildenv') {
              sh 'mvn -version'
              sh './gradlew -s --version'
              sh 'kubectl version'
              sh 'docker --version'
              sh 'az --version'
              sh 'gcloud --version'
              sh 'java -version'

              sh "sed -i 's/dockerImageSuffix=.*/dockerImageSuffix=-staging/' gradle.properties"
              sh "sed -i 's/registryUrl=.*/registryUrl=docker.repo.strapdata.com/' gradle.properties"
              sh "sed -i 's/registryUsername=.*/registryUsername=${REGISTRY_USER}/' gradle.properties"
              sh "sed -i 's/registryPassword=.*/registryPassword=${REGISTRY_PASSWORD}/' gradle.properties"
              sh "sed -i 's/registryEmail=.*/registryEmail=barth@strapdata.com/' gradle.properties"
              sh "sed -i 's@dockerImagePrefix=.*@dockerImagePrefix=docker.repo.strapdata.com/strapkop/@' gradle.properties"
              sh "cat gradle.properties"
            }
          }

          stage("build") {
            parallel(
                    setup_aks: {
                      container('azure') {
                        sh "az login --service-principal -u ${AZ_APP_ID} -p ${AZ_PASSWORD} --tenant ${AZ_TENANT_ID}"
                        aksCreating = true
                        sh 'printenv'
                        sh './gradlew :test:setupAks'
                        aksCreated = true
                        aksCreating = false
                      }
                    },
                    build: {
                      container('buildenv') {
                        sh "docker login -u ${REGISTRY_USER} -p ${REGISTRY_PASSWORD} docker.repo.strapdata.com"
                        sh "./gradlew -s build"
                        sh "./gradlew -s dockerBuild"
                        sh "./gradlew -s dockerPush"
                      }
                    }
            )
          }

          stage('integration tests') {
            container('buildenv') {
              sh "az login --service-principal -u ${AZ_APP_ID} -p ${AZ_PASSWORD} --tenant ${AZ_TENANT_ID}"
              sh "./test/aks/get-credentials"
              sh './gradlew -s :test:test'
            }
          }

          if (scm.branches[0].name == "master") {
            stage('release') {
              container('buildenv') {
                sh './gradlew -s clean dockerPushAllVersions -PdockerImageSuffix=""'
              }
            }
          }
        }
        catch (exc) {
          stage('print logs') {
            container('buildenv') {
              if (aksCreated) {
                sh './test/lib/logs-operator || true'
                sh 'kubectl logs dc1-elassandra-0  sidecar || true'
                sh 'kubectl logs dc1-elassandra-0  elassandra || true'
                sh 'kubectl get all -o yaml'
                sh 'kubectl get crd -o yaml'
                sh 'kubectl get pvc -o yaml'
                sh 'kubectl get cm -o yaml'
                sh 'kubectl get edc -o yaml || true'
                sh 'kubectl get eback -o yaml || true'
              }
            }
          }
          throw exc
        }
        finally {
          stage('cleanup aks') {
            if (aksCreated || aksCreating) {
              container('buildenv') {
                sh './test/cleanup-aks'
              }
            }
          }
        }
      }
    }
  }
}
