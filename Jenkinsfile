// Issue #3 — Pipeline de deploy automatizado das funções serverless.
//
// Regra: push na branch main -> build, teste e deploy automático via
// Serverless Framework, sem intervenção manual.
//
// Pré-requisitos no Jenkins:
//   - Job do tipo "Multibranch Pipeline" (ou Pipeline com GitHub Branch
//     Source) apontando para este repositório, para que env.BRANCH_NAME
//     e as condições `when { branch 'main' }` funcionem.
//   - Plugin GitHub configurado com webhook em <jenkins-url>/github-webhook/
//     para disparar o build a cada push (trigger `githubPush()` abaixo).
//     Sem GitHub, troque o trigger por `pollSCM('H/5 * * * *')`.
//   - Credencial cadastrada em Manage Jenkins > Credentials com o id
//     definido em CLOUD_CREDENTIALS_ID, contendo as chaves de acesso do
//     provedor de nuvem escolhido (ainda não definido neste projeto).
//     Troque o bloco `withCredentials` abaixo pelo binding específico do
//     provedor quando ele for escolhido, por exemplo:
//       - AWS: withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: ...]])
//       - Azure: withCredentials([azureServicePrincipal(...)])
//       - GCP: withCredentials([file(credentialsId: ..., variable: 'GOOGLE_APPLICATION_CREDENTIALS')])

pipeline {
    agent any

    triggers {
        githubPush()
    }

    parameters {
        choice(name: 'STAGE', choices: ['dev', 'prod'], description: 'Estágio do Serverless Framework para o deploy')
    }

    environment {
        CLOUD_CREDENTIALS_ID = 'serverless-deploy-credentials'
    }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Install dependencies') {
            steps {
                sh 'npm ci'
            }
        }

        stage('Lint & Test') {
            steps {
                sh 'npm run lint'
                sh 'npm test'
            }
        }

        stage('Package') {
            steps {
                sh "npx serverless package --stage ${params.STAGE}"
            }
        }

        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: env.CLOUD_CREDENTIALS_ID,
                    usernameVariable: 'CLOUD_ACCESS_KEY_ID',
                    passwordVariable: 'CLOUD_SECRET_ACCESS_KEY'
                )]) {
                    sh "npx serverless deploy --stage ${params.STAGE} --verbose"
                }
            }
        }
    }

    post {
        failure {
            script {
                // GIT_PREVIOUS_SUCCESSFUL_COMMIT é preenchido automaticamente
                // pelo plugin Git no passo `checkout scm` acima.
                if (env.BRANCH_NAME == 'main' && env.GIT_PREVIOUS_SUCCESSFUL_COMMIT) {
                    echo "Deploy falhou. Rollback automático para o último commit com deploy bem-sucedido: ${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}"
                    sh "git checkout ${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}"
                    withCredentials([usernamePassword(
                        credentialsId: env.CLOUD_CREDENTIALS_ID,
                        usernameVariable: 'CLOUD_ACCESS_KEY_ID',
                        passwordVariable: 'CLOUD_SECRET_ACCESS_KEY'
                    )]) {
                        sh "npx serverless deploy --stage ${params.STAGE} --verbose"
                    }
                    echo "Rollback concluído para o commit ${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT}."
                } else {
                    echo 'Rollback automático não disponível (sem build anterior bem-sucedido). Intervenção manual: npx serverless rollback.'
                }
            }
        }
    }
}
