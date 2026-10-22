pipeline {
    agent any

    // Disparo por push (webhook do GitHub -> Jenkins). Fallback: polling a cada 5 min.
    triggers {
        githubPush()
        pollSCM('H/5 * * * *')
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        // Preenchido quando o provedor de nuvem for definido (credenciais via Jenkins Credentials)
        DEPLOY_ENV = 'production'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // Os estágios abaixo só rodam em push na main
        stage('Build') {
            when { branch 'main' }
            steps {
                echo 'TODO: build (definir quando a stack/nuvem for escolhida)'
            }
        }

        stage('Test') {
            when { branch 'main' }
            steps {
                echo 'TODO: testes'
            }
        }

        stage('Deploy') {
            when { branch 'main' }
            steps {
                echo "TODO: deploy via IaC da versão ${env.GIT_COMMIT}"
            }
        }
    }

    post {
        success {
            // Guarda o último commit implantado com sucesso para rollback
            script {
                writeFile file: '.last_good_commit', text: env.GIT_COMMIT
                archiveArtifacts artifacts: '.last_good_commit', allowEmptyArchive: true
            }
        }
        failure {
            script {
                if (env.BRANCH_NAME == 'main') {
                    // Rollback automático para a última versão estável conhecida
                    echo "TODO: rollback para ${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}"
                }
            }
        }
    }
}
