package com.stanfy.spoon.gradle

import com.android.build.gradle.AppPlugin
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.TestPlugin
import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask
import com.android.build.gradle.internal.test.TestApplicationTestData
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin

/**
 * Gradle plugin for Spoon.
 */
class SpoonPlugin implements Plugin<Project> {

    /** Task name prefix. */
    private static final String TASK_PREFIX = "spoon"

    @Override
    void apply(final Project project) {

        if (!project.plugins.findPlugin(AppPlugin) && !project.plugins.findPlugin(LibraryPlugin) && !isTestPlugin(project)) {
            throw new IllegalStateException("Android plugin is not found")
        }

        project.extensions.add "spoon", SpoonExtension

        def spoonTask = project.task(TASK_PREFIX) {
            group = JavaBasePlugin.VERIFICATION_GROUP
            description = "Runs all the instrumentation test variations on all the connected devices"
        }
        BaseExtension android = project.android
        if (isTestPlugin(project)) {
            TestExtension testExtension = project.android
            def targetVariantName = testExtension.targetVariant
            project.afterEvaluate {
                project.tasks.all { Task task ->
                    println "Project task: " + project.name + " " + task.name

                    if (task instanceof DeviceProviderInstrumentTestTask) {
                        DeviceProviderInstrumentTestTask deviceProviderInstrumentTestTask = (DeviceProviderInstrumentTestTask) task;
                        if (deviceProviderInstrumentTestTask != null) {
                            if (deviceProviderInstrumentTestTask.testData instanceof TestApplicationTestData) {
                                TestApplicationTestData testApplicationTestData = deviceProviderInstrumentTestTask.testData;
                                def testApkFile = testApplicationTestData.testApk
                                def mainApkFile = testApplicationTestData.mainApk
                                def taskName = "${TASK_PREFIX}${targetVariantName.capitalize()}"

                                def instrumentTaskDependencies = deviceProviderInstrumentTestTask.getTaskDependencies().getDependencies(deviceProviderInstrumentTestTask)
                                instrumentTaskDependencies.each {dependTask ->
                                    printDependencyTask(dependTask)
                                    println "Depends on task " + dependTask.name + " " + dependTask.project.name + " " + dependTask.class.name
                                }
                                SpoonRunTask spoonRunTask = createTask(project, mainApkFile, testApkFile, taskName, targetVariantName)
                                spoonRunTask.dependsOn instrumentTaskDependencies
                                spoonRunTask.configure {
                                    title = "$project.name $targetVariantName"
                                    description = "Runs instrumentation tests on all the connected devices for '${targetVariantName}' variation and generates a report with screenshots"
                                }

                            }
                        }
                    }
                }
            }

        } else {
            android.testVariants.all { TestVariant variant ->

                String taskName = "${TASK_PREFIX}${variant.name.capitalize()}"
                List<SpoonRunTask> tasks = createTask(variant, project, "")
                tasks.each {
                    it.configure {
                        title = "$project.name $variant.name"
                        description = "Runs instrumentation tests on all the connected devices for '${variant.name}' variation and generates a report with screenshots"
                    }
                }

                spoonTask.dependsOn tasks

                project.tasks.addRule(patternString(taskName)) { String ruleTaskName ->
                    if (ruleTaskName.startsWith(taskName)) {
                        String size = (ruleTaskName - taskName).toLowerCase(Locale.US)
                        if (isValidSize(size)) {
                            List<SpoonRunTask> sizeTasks = createTask(variant, project, size.capitalize())
                            sizeTasks.each {
                                it.configure {
                                    title = "$project.name $variant.name - $size tests"
                                    testSize = size
                                }
                            }
                        }
                    }
                }
            }

        }
        project.tasks.addRule(patternString("spoon")) { String ruleTaskName ->
            if (ruleTaskName.startsWith("spoon")) {
                String suffix = lowercase(ruleTaskName - "spoon")
                if (android.testVariants.find { suffix.startsWith(it.name) } != null) {
                    // variant specific, not our case
                    return
                }
                String size = suffix.toLowerCase(Locale.US)
                if (isValidSize(size)) {
                    def variantTaskNames = spoonTask.taskDependencies.getDependencies(spoonTask).collect() { it.name }
                    project.task(ruleTaskName, dependsOn: variantTaskNames.collect() { "${it}${size.capitalize()}" })
                }
            }
        }

    }

    private boolean isTestPlugin(Project project) {
        project.plugins.findPlugin(TestPlugin)
    }

    private static boolean isValidSize(String size) {
        return size in ['small', 'medium', 'large']
    }

    private static String lowercase(final String s) {
        return s[0].toLowerCase(Locale.US) + s.substring(1)
    }

    private static String patternString(final String taskName) {
        return "Pattern: $taskName<TestSize>: run instrumentation tests of particular size"
    }

    private void printDependencyTask(Task task) {
        task.getTaskDependencies().getDependencies(task).each {dependTask ->
            println "Depends on task " + dependTask.name + " " + dependTask.project.name + " " + dependTask.class.name
        }
    }

    private static List<SpoonRunTask> createTask(
            final TestVariant testVariant, final Project project, final String suffix) {
        if (testVariant.outputs.size() > 1) {
            throw new UnsupportedOperationException("Spoon plugin for gradle currently does not support abi/density splits for test apks")
        }
        SpoonExtension config = project.spoon
        return testVariant.testedVariant.outputs.collect { def projectOutput ->
            SpoonRunTask task = project.tasks.create("${TASK_PREFIX}${testVariant.name.capitalize()}${suffix}", SpoonRunTask)
            task.configure {
                group = JavaBasePlugin.VERIFICATION_GROUP

                def instrumentationPackage = testVariant.outputs[0].outputFile
                if (projectOutput instanceof ApkVariantOutput) {
                    applicationApk = projectOutput.outputFile
                } else {
                    // This is a hack for library projects.
                    // We supply the same apk as an application and instrumentation to the soon runner.
                    applicationApk = instrumentationPackage
                }
                instrumentationApk = instrumentationPackage

                File outputBase = config.baseOutputDir
                if (!outputBase) {
                    outputBase = new File(project.buildDir, "spoon")
                }
                output = new File(outputBase, projectOutput.dirName)

                debug = config.debug
                ignoreFailures = config.ignoreFailures
                devices = config.devices
                allDevices = !config.devices
                noAnimations = config.noAnimations
                failIfNoDeviceConnected = config.failIfNoDeviceConnected
                if (config.adbTimeout != -1) {
                    // Timeout is defined in seconds in the config.
                    adbTimeout = config.adbTimeout * 1000
                }

                testSize = SpoonRunTask.TEST_SIZE_ALL

                if (config.className) {
                    className = config.className
                    if (config.methodName) {
                        methodName = config.methodName
                    }
                }
                if (config.instrumentationArgs) {
                    instrumentationArgs = config.instrumentationArgs
                }

                if (config.numShards > 0) {
                    if (config.shardIndex >= config.numShards) {
                        throw new UnsupportedOperationException("shardIndex needs to be < numShards");
                    }
                    numShards = config.numShards
                    shardIndex = config.shardIndex
                }

                dependsOn projectOutput.assemble, testVariant.assemble
            }
            task.outputs.upToDateWhen { false }
            return task
        } as List<SpoonRunTask>
    }

    private static SpoonRunTask createTask(final Project project, File applicationApkFile, File instrumentApkFile, String taskName, String projectOutputDir) {
        SpoonRunTask task = project.tasks.create(taskName, SpoonRunTask)
        SpoonExtension config = project.spoon
        task.configure {
            group = JavaBasePlugin.VERIFICATION_GROUP

            applicationApk = applicationApkFile
            instrumentationApk = instrumentApkFile

            File outputBase = config.baseOutputDir
            if (!outputBase) {
                outputBase = new File(project.buildDir, "spoon")
            }
            output = new File(outputBase, projectOutputDir)

            debug = config.debug
            ignoreFailures = config.ignoreFailures
            devices = config.devices
            allDevices = !config.devices
            noAnimations = config.noAnimations
            failIfNoDeviceConnected = config.failIfNoDeviceConnected
            if (config.adbTimeout != -1) {
                // Timeout is defined in seconds in the config.
                adbTimeout = config.adbTimeout * 1000
            }

            testSize = SpoonRunTask.TEST_SIZE_ALL

            if (config.className) {
                className = config.className
                if (config.methodName) {
                    methodName = config.methodName
                }
            }
            if (config.instrumentationArgs) {
                instrumentationArgs = config.instrumentationArgs
            }

            if (config.numShards > 0) {
                if (config.shardIndex >= config.numShards) {
                    throw new UnsupportedOperationException("shardIndex needs to be < numShards");
                }
                numShards = config.numShards
                shardIndex = config.shardIndex
            }
        }
        task.outputs.upToDateWhen { false }
        return task

    }

}
