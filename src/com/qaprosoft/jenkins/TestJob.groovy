package com.qaprosoft.jenkins

@Grab('org.yaml:snakeyaml:1.18')
import org.yaml.snakeyaml.*
import org.yaml.snakeyaml.constructor.*

import static java.util.UUID.randomUUID


def runJob() {
    def jobParameters = setJobType("${suite}")
    def mobileGoals = ""

    node(jobParameters.get("node")) {
        timestamps {
            this.prepare(jobParameters)

            this.repoClone()

            this.getResources()

	    if (params["device"] != null && !params["device"].isEmpty() && !params["device"].equals("NULL")) {
                mobileGoals = this.setupForMobile("${device}", jobParameters)
            }

            this.runTests(jobParameters, mobileGoals)

            this.reportingResults()

            this.cleanWorkSpace()
        }
    }
}

def setJobType(String suiteInfo) {

    switch(suiteInfo) {
        case ~/^(?!.*web).*api.*$/:
            println "Suite Type: API";
            return setJobParameters("API", "qps-slave")
        case ~/^.*web.*$/:
            println "Suite Type: Web";
            return setJobParameters("*", "qps-slave")
        case ~/^.*android.*$/:
            println "Suite Type: Android";
            return setJobParameters("ANDROID", "android")
        case ~/^.*ios.*$/:
            println "Suite Type: iOS";
            //TODO: Need to improve this to be able to handle where emulator vs. physical tests should be run.
            return setJobParameters("ios", "ios")
        default:
            println "Suite Type: Default";
            return setJobParameters("*", "master")
    }
}

def setJobParameters(String platform, String nodeType) {
    def jobProperties = [:]
    jobProperties.put("platform", platform)
    jobProperties.put("node", nodeType)
    return jobProperties
}

def prepare(Map jobParameters) {
    stage('Preparation') {
        currentBuild.displayName = "#${BUILD_NUMBER}|${env.env}"
	if (!isParamEmpty("${CARINA_CORE_VERSION}")) {
	    currentBuild.displayName += "|" + "${CARINA_CORE_VERSION}" 
	}
	if (!isParamEmpty(params["device"])) {
	    currentBuild.displayName += "|${device}"
	}
	if (!isParamEmpty(params["browser"])) {
	    currentBuild.displayName += "|${browser}"
	}
	if (!isParamEmpty(params["browser_version"])) {
	    currentBuild.displayName += "|${browser_version}"
	}
	
        currentBuild.description = "${suite}"
    }
}

def repoClone() {
    stage('Checkout GitHub Repository') {
        git branch: '${branch}', credentialsId: 'vdelendik', url: '${repository}', changelog: false, poll: false, shallow: true
    }
}

def getResources() {
    stage("Download Resources") {
        if (isUnix()) {
            sh "'mvn' -B -U -f pom.xml clean process-resources process-test-resources -Dcarina-core_version=$CARINA_CORE_VERSION"
        } else {
            bat(/"mvn" -B -U -f pom.xml clean process-resources process-test-resources -Dcarina-core_version=$CARINA_CORE_VERSION/)
        }
    }
}

def setupForMobile(String devicePattern, Map jobParameters) {

    def goalMap = [:]

    stage("Mobile Preparation") {
        if (jobParameters.get("platform").toString().equalsIgnoreCase("android")) {
            goalMap = setupGoalsForAndroid(goalMap)
        } else {
            goalMap = setupGoalsForiOS(goalMap)
        }
       	echo "DEVICE: " +  devicePattern


        if (!devicePattern.equalsIgnoreCase("all")) {
            goalMap.put("capabilities.deviceName", devicePattern)
	}

	//TODO: remove after resolving issues with old mobile capabilities generator
	goalMap.put("capabilities.platformName", jobParameters.get("platform").toString().toUpperCase())

        goalMap.put("capabilities.newCommandTimeout", "180")

        goalMap.put("retry_count", "${retry_count}")
        goalMap.put("thread_count", "${thread_count}")
        goalMap.put("retry_interval", "1000")
        goalMap.put("implicit_timeout", "30")
        goalMap.put("explicit_timeout", "60")
        goalMap.put("java.awt.headless", "true")

    }
    return buildOutGoals(goalMap)
}

def setupGoalsForAndroid(Map<String, String> goalMap) {

    echo "ENV: " +  params["env"]

    goalMap.put("mobile_app_clear_cache", "true")

    goalMap.put("capabilities.platform", "ANDROID")
    goalMap.put("capabilities.platformName", "ANDROID")
    goalMap.put("capabilities.deviceName", "*")

    goalMap.put("capabilities.appPackage", "")
    goalMap.put("capabilities.appActivity", "")

    goalMap.put("capabilities.autoGrantPermissions", "true")
    goalMap.put("capabilities.noSign", "true")
    goalMap.put("capabilities.STF_ENABLED", "true")

    return goalMap
}


def setupGoalsForiOS(Map<String, String> goalMap) {


    goalMap.put("capabilities.platform", "IOS")
    goalMap.put("capabilities.platformName", "IOS")
    goalMap.put("capabilities.deviceName", "*")

    goalMap.put("capabilities.appPackage", "")
    goalMap.put("capabilities.appActivity", "")

    goalMap.put("capabilities.noSign", "false")
    goalMap.put("capabilities.autoGrantPermissions", "false")
    goalMap.put("capabilities.autoAcceptAlerts", "true")

    goalMap.put("capabilities.STF_ENABLED", "false")

    // remove after fixing
    goalMap.put("capabilities.automationName", "XCUITest")

    return goalMap
}


def buildOutGoals(Map<String, String> goalMap) {
    def goals = ""

    goalMap.each { k, v -> goals = goals + " -D${k}=${v}"}

    return goals
}

def runTests(Map jobParameters, String mobileGoals) {
    stage('Run Test Suite') {
        def goalMap = [:]

        def DEFAULT_BASE_MAVEN_GOALS = "-Ds3_local_storage=/opt/apk -Dhockeyapp_local_storage=/opt/apk -Dcarina-core_version=$CARINA_CORE_VERSION -f pom.xml \
			-Dci_run_id=$ci_run_id -Dcore_log_level=$CORE_LOG_LEVEL -Demail_list=$email_list \
			-Dmaven.test.failure.ignore=true -Dselenium_host=$SELENIUM_HOST -Dmax_screen_history=1 \
			-Dinit_retry_count=0 -Dinit_retry_interval=10 $ZAFIRA_BASE_CONFIG clean test"

 	uuid = "${ci_run_id}"
	echo "uuid: " + uuid
        if (uuid.isEmpty()) {
            uuid = randomUUID() as String
        }
	echo "uuid: " + uuid

        def zafiraEnabled = "false"
        def regressionVersionNumber = new Date().format('yyMMddhhmm')
        if ("${DEFAULT_BASE_MAVEN_GOALS}".contains("zafira_enabled=true")) {
            zafiraEnabled = "true"
        }

        if ("${develop}".contains("true")) {
            echo "Develop Flag has been Set, disabling interaction with Zafira Reporting."
            zafiraEnabled = "false"
        }

        goalMap.put("env", params["env"])

	if (params["browser"] != null && !params["browser"].isEmpty()) {
            goalMap.put("browser", params["browser"])
	}

	if (params["auto_screenshot"] != null) {
            goalMap.put("auto_screenshot", params["auto_screenshot"])
	}

	if (params["keep_all_screenshots"] != null) {
            goalMap.put("keep_all_screenshots", params["keep_all_screenshots"])
	}

	goalMap.put("zafira_enabled", "${zafiraEnabled}")
        goalMap.put("ci_run_id", "${uuid}")
        goalMap.put("ci_url", "$JOB_URL")
        goalMap.put("ci_build", "$BUILD_NUMBER")
        goalMap.put("platform", jobParameters.get("platform"))

        def mvnBaseGoals = DEFAULT_BASE_MAVEN_GOALS + buildOutGoals(goalMap) + mobileGoals

        if ("${JACOCO_ENABLE}".equalsIgnoreCase("true")) {
            echo "Enabling jacoco report generation goals."
            mvnBaseGoals += " jacoco:instrument"
        }

        mvnBaseGoals += " ${overrideFields}"
        mvnBaseGoals = mvnBaseGoals.replace(", ", ",")

        if (isUnix()) {
            suiteNameForUnix = "${suite}".replace("\\", "/")
            echo "Suite for Unix: ${suiteNameForUnix}"
            sh "'mvn' -B ${mvnBaseGoals} -Dsuite=${suiteNameForUnix} -Dzafira_report_folder=./reports/qa -Dreport_url=$JOB_URL$BUILD_NUMBER/eTAF_Report"
        } else {
            suiteNameForWindows = "${suite}".replace("/", "\\")
            echo "Suite for Windows: ${suiteNameForWindows}"
            bat(/"mvn" -B ${mvnBaseGoals} -Dsuite=${suiteNameForWindows} -Dzafira_report_folder=.\reports\qa -Dreport_url=$JOB_URL$BUILD_NUMBER\eTAF_Report/)
        }

        archiveArtifacts artifacts: '**/jacoco.exec', fingerprint: true, allowEmptyArchive: true
        // https://github.com/jenkinsci/pipeline-aws-plugin#s3upload
        withAWS(region: 'us-west-1',credentials:'aws-jacoco-token') {
            s3Upload(bucket:"$JACOCO_BUCKET", path:"$JOB_NAME/$BUILD_NUMBER/jacoco-it.exec", includePathPattern:'**/jacoco.exec')
        }

        this.setTestResults()
    }
}

def setTestResults() {
    //Need to do a forced failure here in case the report doesn't have PASSED or PASSED KNOWN ISSUES in it.
    checkReport = readFile("./reports/qa/emailable-report.html")
    if (!checkReport.contains("PASSED:") && !checkReport.contains("PASSED (known issues):") && !checkReport.contains("SKIP_ALL:")) {
        echo "Unable to Find (Passed) or (Passed Known Issues) within the eTAF Report."
        currentBuild.result = 'FAILURE'
    } else if (checkReport.contains("SKIP_ALL:")) {
        currentBuild.result = 'UNSTABLE'
    }
}

def reportingResults() {
    stage('Results') {
        if (fileExists("./reports/qa/zafira-report.html")) {
            echo "Zafira Report File Found, Publishing File"
            publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './reports/qa/', reportFiles: 'zafira-report.html', reportName: 'eTAF_Report'])
        } else {
            echo "Zafira Report File Not Found, Publishing E-Mail File"
            publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './reports/qa/', reportFiles: 'emailable-report.html', reportName: 'eTAF_Report'])

        }
        publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './target/surefire-reports/', reportFiles: 'index.html', reportName: 'Full TestNG HTML Report'])
        publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, keepAll: true, reportDir: './target/surefire-reports/', reportFiles: 'emailable-report.html', reportName: 'TestNG Summary HTML Report'])
    }
}

def cleanWorkSpace() {
    stage('Wipe out Workspace') {
        deleteDir()
    }
}

def isParamEmpty(String value) {
    if (value == null || value.isEmpty() || value.equals("NULL")) {
	return true
    } else {
	return false
    }
}


def runJacocoMergeJob() {
    node(jobParameters.get("node")) {
        timestamps {
            this.runMerge()
            this.cleanWorkSpace()
        }
    }
}

def runMerge() {
    stage('Run Test Suite') {
        // download jacoco-it.exec coverage reports
        withAWS(region: 'us-west-1',credentials:'aws-jacoco-token') {
            files = s3FindFiles(bucket:"$JACOCO_BUCKET", path:'**/**/jacoco-it.exec')
            files.each (files) { file ->
              println "file: " + file
            }

            //s3Download(bucket:"$JACOCO_BUCKET", path:"$JOB_NAME/$BUILD_NUMBER/jacoco-it.exec", includePathPattern:'**/jacoco.exec')
        }

        // merge all reports into the single file

        // upload merged file into another bucket/key

        // remove old binary reports

    }
}
