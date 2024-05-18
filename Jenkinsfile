pipeline {
  agent { label 'Linux-Office03' }
  options {
    buildDiscarder(logRotator(daysToKeepStr:'10'))
    timeout(time: 15, unit: 'MINUTES')
    skipDefaultCheckout()
    disableConcurrentBuilds()
  }

  stages {
    stage ('Build sonarlint-language-server') {
      steps {
        checkout([$class: 'GitSCM', branches: scm.branches, extensions: scm.extensions + [[$class: 'CleanCheckout']], userRemoteConfigs: scm.userRemoteConfigs])
        script {
          withEnv(["MVN_HOME=${tool name: 'Maven 3', type: 'maven'}", "JAVA_HOME=${tool name: 'JDK17', type: 'jdk'}"]) {
            sh "$MVN_HOME/bin/mvn -Dmaven.test.skip=true clean deploy"
          }
        }
        archiveArtifacts artifacts: 'target/*.jar'
      }
    }
  }
}
