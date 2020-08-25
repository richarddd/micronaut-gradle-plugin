package io.micronaut.gradle.docker;

import com.bmuschko.gradle.docker.DockerExtension;
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import com.bmuschko.gradle.docker.tasks.image.Dockerfile;
import io.micronaut.gradle.MicronautExtension;
import io.micronaut.gradle.MicronautRuntime;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;

import java.io.File;
import java.util.*;

import static org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME;

public class MicronautDockerPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        TaskContainer tasks = project.getTasks();
        ExtensionContainer extensions = project.getExtensions();
        extensions.create("docker", DockerExtension.class);
        MicronautExtension micronautExtension = extensions.getByType(MicronautExtension.class);
        File applicationLayout = new File(project.getBuildDir(), "layers");
        TaskProvider<Jar> runnerJar = tasks.register("runnerJar", Jar.class, jar -> {
            jar.dependsOn(tasks.findByName("classes"));
            jar.getArchiveClassifier().set("runner");
            SourceSetContainer sourceSets = project
                    .getExtensions().getByType(SourceSetContainer.class);

            SourceSet mainSourceSet = sourceSets
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            FileCollection dirs = mainSourceSet.getOutput().getClassesDirs();

            jar.from(dirs);
        });

        // NOTE: Has to be an anonymous inner class otherwise incremental build does not work
        // DO NOT REPLACE WITH LAMBDA
        //noinspection Convert2Lambda
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                Jar jar = runnerJar.get();
                jar.manifest(manifest -> {
                    Map<String, Object> attrs = new HashMap<>(2);
                    JavaApplication javaApplication = project.getExtensions().getByType(JavaApplication.class);
                    String mainClassName = javaApplication.getMainClassName();
                    Configuration runtimeClasspath = project.getConfigurations()
                            .getByName(RUNTIME_CLASSPATH_CONFIGURATION_NAME);

                    List<String> classpath = new ArrayList<>();
                    for (File file : runtimeClasspath) {
                        classpath.add("libs/" + file.getName());
                    }
                    classpath.add("resources/");

                    attrs.put("Main-Class", mainClassName);
                    attrs.put("Class-Path", String.join(" ", classpath));
                    manifest.attributes(attrs);
                });
            }
        });


        TaskProvider<Task> buildLayersTask = tasks.register("buildLayers", task -> {
            task.dependsOn(runnerJar);

            Configuration runtimeClasspath = project.getConfigurations()
                    .getByName(RUNTIME_CLASSPATH_CONFIGURATION_NAME);
            TaskOutputs jar = runnerJar.get().getOutputs();
            FileCollection jarFiles = jar.getFiles();
            SourceSetContainer sourceSets = project
                    .getExtensions().getByType(SourceSetContainer.class);

            SourceSet mainSourceSet = sourceSets
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            Set<File> resourceDirs = mainSourceSet.getResources().getSrcDirs();

            TaskInputs inputs = task.getInputs();
            inputs.files(runtimeClasspath);
            inputs.files(jarFiles);
            inputs.files(resourceDirs);


            task.getOutputs().dir(applicationLayout);
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds application layers for use in a Docker container");

            // NOTE: Has to be an anonymous inner class otherwise incremental build does not work
            // DO NOT REPLACE WITH LAMBDA
            //noinspection Convert2Lambda
            task.doLast(new Action<Task>() {
                @Override
                public void execute(Task t) {
                    project.copy(copy ->
                            copy.from(runtimeClasspath)
                                    .into(new File(applicationLayout, "libs"))
                    );
                    project.copy(copy ->
                            copy.from(jarFiles)
                                    .into(applicationLayout)
                                    .rename(s -> "application.jar")
                    );
                    project.mkdir(new File(applicationLayout, "resources"));
                    project.copy(copy ->
                            copy.from(project.files(resourceDirs))
                                    .into(new File(applicationLayout, "resources"))
                    );
                }
            });
        });

        Task assemble = tasks.findByName("assemble");
        if (assemble != null) {
            assemble.dependsOn(buildLayersTask);
        }

        configureDockerBuild(project, tasks, micronautExtension, buildLayersTask);
        configureNativeDockerBuild(project, tasks, micronautExtension, buildLayersTask);
    }

    private void configureDockerBuild(Project project, TaskContainer tasks, MicronautExtension micronautExtension, TaskProvider<Task> buildLayersTask) {
        File f = project.file("Dockerfile");

        TaskProvider<Dockerfile> dockerFileTask;
        if (f.exists()) {
            dockerFileTask = tasks.register("createDockerFile", Dockerfile.class, task -> {
                        task.setGroup(BasePlugin.BUILD_GROUP);
                        task.setDescription("Builds a Docker File");
                        task.instructionsFromTemplate(f);
                    }
            );
        } else {
            dockerFileTask = tasks.register("createDockerFile", Dockerfile.class);
            dockerFileTask.configure(task -> {
                task.setGroup(BasePlugin.BUILD_GROUP);
                task.setDescription("Builds a Docker File");
                DockerSettings docker = micronautExtension.getDocker();
                MicronautRuntime micronautRuntime = micronautExtension.getRuntime().get();
                String from = docker.getFrom().getOrNull();
                if (from == null) {

                    switch (micronautRuntime) {
                        case ORACLE_FUNCTION:
                            task.from(new Dockerfile.From("fnproject/fn-java-fdk:" + getProjectFnVersion()));
                            task.workingDir("/function");
                            task.copyFile("build/layers/libs", "/function/app/");
                            task.copyFile("build/layers/resources", "/function/app/");
                            task.copyFile("build/layers/application.jar", "/function/app/");
                            task.defaultCommand("io.micronaut.oci.function.http.HttpFunction::handleRequest");
                        break;
                        case LAMBDA:
                            // TODO
                        default:
                            task.from(new Dockerfile.From("openjdk:14-alpine"));
                            setupResources(task);
                            task.exposePort(docker.getPorts());
                            task.defaultCommand("java", "-jar", "/home/app/application.jar");
                    }
                }


                task.dependsOn(buildLayersTask);
            });
        }
        TaskProvider<DockerBuildImage> dockerBuildTask = tasks.register("buildDockerImage", DockerBuildImage.class);
        dockerBuildTask.configure(task -> {
            DockerSettings docker = micronautExtension.getDocker();
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds a Docker Image");
            task.getInputDir().set(project.getRootDir());
            task.getDockerFile()
                    .convention(dockerFileTask.flatMap(Dockerfile::getDestFile));
            task.getImages().set(docker.getTag().orElse(project.getName()).map(Collections::singletonList));
        });

        TaskProvider<DockerPushImage> pushDockerImage = tasks.register("pushDockerImage", DockerPushImage.class);
        pushDockerImage.configure(task -> {
            task.dependsOn(dockerBuildTask);
            task.setGroup(BasePlugin.UPLOAD_GROUP);
            task.setDescription("Pushes a Docker Image");
            task.getImages().set(dockerBuildTask.flatMap(DockerBuildImage::getImages));
        });
    }

    private String getProjectFnVersion() {
        return "1.0.105";
    }

    private void configureNativeDockerBuild(Project project, TaskContainer tasks, MicronautExtension micronautExtension, TaskProvider<Task> buildLayersTask) {
        File f = project.file("Dockerfile.native");

        TaskProvider<NativeImageDockerfile> dockerFileTask;
        if (f.exists()) {
            dockerFileTask = tasks.register("createNativeDockerFile", NativeImageDockerfile.class, task -> {
                task.instructionsFromTemplate(f);
                task.dependsOn(buildLayersTask);
            });
        } else {
            dockerFileTask = tasks.register("createNativeDockerFile", NativeImageDockerfile.class);
            dockerFileTask.configure(task -> task.dependsOn(buildLayersTask));
        }
        TaskProvider<DockerBuildImage> dockerBuildTask = tasks.register("buildDockerNativeImage", DockerBuildImage.class);
        dockerBuildTask.configure(task -> {
            DockerSettings docker = micronautExtension.getDocker();
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds a Native Docker Image using GraalVM");
            task.getInputDir().set(project.getRootDir());
            task.getDockerFile()
                    .convention(dockerFileTask.flatMap(Dockerfile::getDestFile));
            task.getImages().set(docker.getTag().orElse(project.getName()).map(Collections::singletonList));
        });

        TaskProvider<DockerPushImage> pushDockerImage = tasks.register("pushDockerNativeImage", DockerPushImage.class);
        pushDockerImage.configure(task -> {
            task.dependsOn(dockerBuildTask);
            task.setGroup(BasePlugin.UPLOAD_GROUP);
            task.setDescription("Pushes a Native Docker Image using GraalVM");
            task.getImages().set(dockerBuildTask.flatMap(DockerBuildImage::getImages));
        });
    }

    static void setupResources(Dockerfile task) {
        task.workingDir("/home/app");
        task.copyFile("build/layers/libs", "/home/app/libs");
        task.copyFile("build/layers/resources", "/home/app/resources");
        task.copyFile("build/layers/application.jar", "/home/app/application.jar");
    }
}
