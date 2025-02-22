/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package io.micronaut.gradle.testresources;

import io.micronaut.gradle.MicronautBasePlugin;
import io.micronaut.gradle.MicronautExtension;
import io.micronaut.gradle.PluginsHelper;
import io.micronaut.gradle.testresources.internal.TestResourcesAOT;
import io.micronaut.gradle.testresources.internal.TestResourcesGraalVM;
import io.micronaut.testresources.buildtools.MavenDependency;
import io.micronaut.testresources.buildtools.ServerUtils;
import io.micronaut.testresources.buildtools.TestResourcesClasspath;
import io.micronaut.testresources.buildtools.VersionInfo;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.PluginManager;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.session.BuildSessionLifecycleListener;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Stream.concat;

/**
 * This plugin integrates with Micronaut Test Resources.
 * It handles the lifecycle of the test resources server
 * and provides configuration so that the user can precisely
 * tweak the behavior of the test resources server.
 */
public class MicronautTestResourcesPlugin implements Plugin<Project> {
    public static final String START_TEST_RESOURCES_SERVICE = "startTestResourcesService";
    public static final String START_TEST_RESOURCES_SERVICE_INTERNAL = "internalStartTestResourcesService";
    public static final String STOP_TEST_RESOURCES_SERVICE = "stopTestResourcesService";
    public static final String GROUP = "Micronaut Test Resources";
    public static final String TESTRESOURCES_CONFIGURATION = "testResourcesService";
    public static final String TESTRESOURCES_ELEMENTS_CONFIGURATION = "testresourcesSettingsElements";
    public static final String MICRONAUT_TEST_RESOURCES_USAGE = "micronaut.test.resources";

    private static final int DEFAULT_CLIENT_TIMEOUT_SECONDS = 60;

    public static void addTestResourcesClientDependencies(Project project, TestResourcesConfiguration config, DependencyHandler dependencies, TaskProvider<StartTestResourcesService> writeTestProperties, Configuration conf) {
        // Would be cleaner to use `config.getEnabled().zip(...)` but for some unclear reason it fails
        conf.getDependencies().addAllLater(config.getVersion().map(v -> {
            if (Boolean.TRUE.equals(config.getEnabled().get())) {
                return Collections.singleton(dependencies.create("io.micronaut.testresources:micronaut-test-resources-client:" + v));
            }
            return Collections.emptyList();
        }));
        conf.getDependencies().addAllLater(config.getEnabled().map(enabled -> {
            if (Boolean.TRUE.equals(enabled)) {
                return Collections.singleton(dependencies.create(project.files(writeTestProperties)));
            }
            return Collections.emptyList();
        }));
        conf.getDependencyConstraints().addAllLater(PluginsHelper.findMicronautVersionAsProvider(project).map(v ->
                Stream.of("micronaut-http-client", "micronaut-bom", "micronaut-inject")
                        .map(artifact -> dependencies.getConstraints().create("io.micronaut:" + artifact, dc -> {
                            dc.because("Aligning version of Micronaut the current Micronaut version");
                            dc.version(version -> version.strictly(v));
                        }))
                        .toList()
        ));
    }

    public void apply(Project project) {
        PluginManager pluginManager = project.getPluginManager();
        pluginManager.apply(JavaPlugin.class);
        pluginManager.apply(MicronautBasePlugin.class);
        configurePlugin(project);
    }

    private void configurePlugin(Project project) {
        Configuration server = createTestResourcesServerConfiguration(project);
        Configuration outgoing = createTestResourcesOutgoingConfiguration(project);
        ProviderFactory providers = project.getProviders();
        Provider<Integer> explicitPort = providers.systemProperty("micronaut.test-resources.server.port").map(Integer::parseInt);
        TestResourcesConfiguration config = createTestResourcesConfiguration(project, explicitPort);
        JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet testResourcesSourceSet = createTestResourcesSourceSet(javaPluginExtension);
        DependencyHandler dependencies = project.getDependencies();
        Configuration testResourcesApi = project.getConfigurations().getByName(testResourcesSourceSet.getImplementationConfigurationName());
        testResourcesApi.getDependencies().addLater(config.getVersion().map(v -> dependencies.create("io.micronaut.testresources:micronaut-test-resources-core:" + v)));
        server.getDependencies().addAllLater(buildTestResourcesDependencyList(project, dependencies, config, testResourcesSourceSet));
        String accessToken = UUID.randomUUID().toString();
        Provider<String> accessTokenProvider = providers.provider(() -> accessToken);
        DirectoryProperty buildDirectory = project.getLayout().getBuildDirectory();
        Provider<Directory> settingsDirectory = config.getSharedServer().flatMap(shared -> {
            DirectoryProperty directoryProperty = project.getObjects().directoryProperty();
            if (Boolean.TRUE.equals(shared)) {
                String namespace = config.getSharedServerNamespace().getOrNull();
                directoryProperty.set(ServerUtils.getDefaultSharedSettingsPath(namespace).toFile());
            }
            return directoryProperty;
        }).orElse(buildDirectory.dir("test-resources-settings"));
        Provider<RegularFile> portFile = buildDirectory.file("test-resources-port.txt");
        Path stopAtEndFile = createStopFile(project);
        TaskContainer tasks = project.getTasks();
        Provider<Boolean> isStandalone = config.getSharedServer().zip(providers.provider(() -> {
            boolean singleTask = project.getGradle().getStartParameter().getTaskNames().size() == 1;
            boolean onlyStartTask = project.getGradle().getTaskGraph()
                    .getAllTasks()
                    .stream()
                    .anyMatch(task -> task.getProject().equals(project) && task.getName().equals(START_TEST_RESOURCES_SERVICE));
            return singleTask && onlyStartTask;
        }), (shared, singleTask) -> shared || singleTask);
        Provider<Directory> cdsDir = buildDirectory.dir("test-resources/cds");
        TaskProvider<StartTestResourcesService> internalStart = createStartServiceTask(server, config, settingsDirectory, accessTokenProvider, tasks, portFile, stopAtEndFile, isStandalone, cdsDir);
        tasks.register(START_TEST_RESOURCES_SERVICE, task -> {
            task.dependsOn(internalStart);
            task.setOnlyIf(t -> config.getEnabled().get());
            task.setGroup(MicronautTestResourcesPlugin.GROUP);
            task.setDescription("Starts the test resources server in standalone mode");
        });
        createStopServiceTask(settingsDirectory, tasks);
        project.afterEvaluate(p -> p.getConfigurations().all(conf -> configureDependencies(project, config, dependencies, internalStart, conf)));
        outgoing.getOutgoing().artifact(internalStart);
        outgoing.getDependencies().addLater(config.getVersion().map(v -> dependencies.create("io.micronaut.testresources:micronaut-test-resources-client:" + v)));
        Configuration testResourcesClasspathConfig = createTestResourcesClasspathConfig(project, config, internalStart);
        PluginManager pluginManager = project.getPluginManager();
        pluginManager.withPlugin("org.graalvm.buildtools.native", unused -> TestResourcesGraalVM.configure(project, tasks, testResourcesClasspathConfig));
        pluginManager.withPlugin("io.micronaut.aot", unused -> TestResourcesAOT.configure(project, config, dependencies, tasks, internalStart, testResourcesClasspathConfig));
        configureServiceReset((ProjectInternal) project, settingsDirectory, stopAtEndFile);
    }

    private Configuration createTestResourcesClasspathConfig(Project project, TestResourcesConfiguration config, TaskProvider<StartTestResourcesService> startTestResourcesServiceTaskProvider) {
        return project.getConfigurations().create("testResourcesClasspath", conf -> {
            conf.setCanBeResolved(true);
            conf.setCanBeConsumed(false);
            conf.attributes(attrs -> {
                ObjectFactory objects = project.getObjects();
                attrs.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.class, Usage.JAVA_RUNTIME));
                attrs.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.class, Bundling.EXTERNAL));
                attrs.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.class, LibraryElements.CLASSES_AND_RESOURCES));
            });
            addTestResourcesClientDependencies(project, config, project.getDependencies(), startTestResourcesServiceTaskProvider, conf);
        });
    }

    private Path createStopFile(Project project) {
        Path stopAtEndFile;
        try {
            File asFile = project.getLayout().getBuildDirectory().file("test-resources/" + UUID.randomUUID()).get().getAsFile();
            File parentDir = asFile.getParentFile();
            if (parentDir.isDirectory() || parentDir.mkdirs()) {
                asFile.deleteOnExit();
                stopAtEndFile = asFile.toPath();
                Files.deleteIfExists(stopAtEndFile);
            } else {
                throw new IOException("Could not create directory for test resources stop file at " + parentDir.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new GradleException("Unable to create temp file", e);
        }
        return stopAtEndFile;
    }

    private SourceSet createTestResourcesSourceSet(JavaPluginExtension javaPluginExtension) {
        return javaPluginExtension.getSourceSets().create("testResources");
    }

    private void createStopServiceTask(Provider<Directory> settingsDirectory, TaskContainer tasks) {
        tasks.register(STOP_TEST_RESOURCES_SERVICE, StopTestResourcesService.class, task -> task.getSettingsDirectory().convention(settingsDirectory));
    }

    private void configureDependencies(Project project, TestResourcesConfiguration config, DependencyHandler dependencies, TaskProvider<StartTestResourcesService> writeTestProperties, Configuration conf) {
        String name = conf.getName();
        if ("developmentOnly".equals(name) || "testRuntimeOnly".equals(name)) {
            addTestResourcesClientDependencies(project, config, dependencies, writeTestProperties, conf);
        }
    }

    private TaskProvider<StartTestResourcesService> createStartServiceTask(Configuration server,
                                                                           TestResourcesConfiguration config,
                                                                           Provider<Directory> settingsDirectory,
                                                                           Provider<String> accessToken,
                                                                           TaskContainer tasks,
                                                                           Provider<RegularFile> portFile,
                                                                           Path stopFile,
                                                                           Provider<Boolean> isStandalone,
                                                                           Provider<Directory> cdsDir) {
        return tasks.register(START_TEST_RESOURCES_SERVICE_INTERNAL, StartTestResourcesService.class, task -> {
            task.setOnlyIf(t -> config.getEnabled().get());
            task.getPortFile().convention(portFile);
            task.getSettingsDirectory().convention(settingsDirectory);
            task.getAccessToken().convention(accessToken);
            task.getExplicitPort().convention(config.getExplicitPort());
            task.getClientTimeout().convention(config.getClientTimeout());
            task.getClasspath().from(server);
            task.getForeground().convention(false);
            task.getStopFile().set(stopFile.toFile());
            task.getStandalone().set(isStandalone);
            task.getClassDataSharingDir().convention(cdsDir);
            task.getUseClassDataSharing().convention(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17));
        });
    }

    private TestResourcesConfiguration createTestResourcesConfiguration(Project project, Provider<Integer> explicitPort) {
        MicronautExtension micronautExtension = PluginsHelper.findMicronautExtension(project);
        TestResourcesConfiguration testResources = micronautExtension.getExtensions().create("testResources", TestResourcesConfiguration.class);
        ProviderFactory providers = project.getProviders();
        testResources.getEnabled().convention(
                micronautExtension.getVersion()
                        .orElse(providers.gradleProperty("micronautVersion"))
                        .map(MicronautTestResourcesPlugin::isAtLeastMicronaut3dot5)
        );
        testResources.getVersion().convention(VersionInfo.getVersion());
        testResources.getExplicitPort().convention(explicitPort);
        testResources.getInferClasspath().convention(true);
        testResources.getClientTimeout().convention(DEFAULT_CLIENT_TIMEOUT_SECONDS);
        testResources.getSharedServer().convention(
                providers.gradleProperty("shared.test.resources")
                        .orElse(providers.systemProperty("shared.test.resources"))
                        .orElse(providers.environmentVariable("SHARED_TEST_RESOURCES"))
                        .orElse("false")
                        .map(str -> {
                            if (str.isEmpty()) {
                                return true;
                            }
                            return Boolean.parseBoolean(str);
                        })
        );
        testResources.getSharedServerNamespace().convention(providers.environmentVariable("SHARED_TEST_RESOURCES_NAMESPACE"));
        return testResources;
    }

    private static boolean isAtLeastMicronaut3dot5(String v) {
        String[] parts = v.split("\\.");
        if (parts.length >= 2) {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major > 3 || (major == 3 && minor >= 5)) {
                return true;
            }
            return false;
        }
        return false;
    }

    private Provider<List<Dependency>> buildTestResourcesDependencyList(Project project, DependencyHandler dependencies, TestResourcesConfiguration config, SourceSet testResourcesSourceSet) {
        return config.getEnabled().zip(config.getInferClasspath(), (enabled, infer) -> {
            if (Boolean.FALSE.equals(enabled)) {
                return Collections.singletonList(dependencies.create(testResourcesSourceSet.getRuntimeClasspath()));
            }
            List<MavenDependency> mavenDependencies = Collections.emptyList();
            if (Boolean.TRUE.equals(infer)) {
                mavenDependencies = project.getConfigurations().getByName("runtimeClasspath")
                        .getAllDependencies()
                        .stream()
                        .filter(ModuleDependency.class::isInstance)
                        .map(ModuleDependency.class::cast)
                        .map(d -> new MavenDependency(d.getGroup(), d.getName(), d.getVersion()))
                        .toList();
            }
            String testResourcesVersion = config.getVersion().get();
            return concat(concat(
                            TestResourcesClasspath.inferTestResourcesClasspath(mavenDependencies, testResourcesVersion)
                                    .stream()
                                    .map(Object::toString),
                            config.getAdditionalModules().getOrElse(Collections.emptyList())
                                    .stream()
                                    .map(m -> "io.micronaut.testresources:micronaut-test-resources-" + m + ":" + testResourcesVersion))
                            .map(dependencies::create),
                    Stream.of(dependencies.create(testResourcesSourceSet.getRuntimeClasspath())))
                    .toList();
        }).orElse(Collections.emptyList());
    }

    private void configureServiceReset(ProjectInternal project,
                                       Provider<Directory> settingsDirectory,
                                       Path shouldStopFile) {
        ServiceRegistry services = project.getServices();
        ListenerManager listenerManager = services.get(ListenerManager.class);
        Field parentField;
        try {
            parentField = listenerManager.getClass().getDeclaredField("parent");

            parentField.setAccessible(true);
            listenerManager = (ListenerManager) parentField.get(parentField.get(listenerManager));
            listenerManager.addListener(new BuildSessionLifecycleListener() {
                @Override
                public void beforeComplete() {
                    try {
                        if (Files.exists(shouldStopFile)) {
                            project.getLogger().debug("Stop file contains " + Files.readAllLines(shouldStopFile));
                            if (Boolean.parseBoolean(Files.readAllLines(shouldStopFile).get(0))) {
                                ServerUtils.stopServer(settingsDirectory.get().getAsFile().toPath());
                            }
                        }
                    } catch (IOException e) {
                        project.getLogger().debug("Test resources server is already stopped", e);
                    }
                }
            });
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new GradleException(e.getMessage(), e);
        }
    }

    private static Configuration createTestResourcesServerConfiguration(Project project) {
        // Legacy configuration was only used in 3.5.0 so it's relatively safe
        Configuration legacyConf = project.getConfigurations().create("testresources", conf -> {
            conf.setCanBeConsumed(false);
            conf.setCanBeResolved(false);
            conf.setDescription("[deprecated] Please use " + MicronautTestResourcesPlugin.TESTRESOURCES_CONFIGURATION + " instead.");
        });
        return project.getConfigurations().create(TESTRESOURCES_CONFIGURATION, conf -> {
            conf.extendsFrom(legacyConf);
            conf.setDescription("Dependencies for the Micronaut test resources service");
            conf.setCanBeConsumed(false);
            conf.setCanBeResolved(true);
        });
    }

    private static Configuration createTestResourcesOutgoingConfiguration(Project project) {
        return project.getConfigurations().create(TESTRESOURCES_ELEMENTS_CONFIGURATION, conf -> {
            conf.setDescription("Provides the Micronaut Test Resources client configuration files");
            conf.setCanBeConsumed(true);
            conf.setCanBeResolved(false);
            conf.attributes(attr -> attr.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, MICRONAUT_TEST_RESOURCES_USAGE)));
        });
    }

}
