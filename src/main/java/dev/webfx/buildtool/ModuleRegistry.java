package dev.webfx.buildtool;

import dev.webfx.buildtool.modulefiles.ResWebFxModuleFile;
import dev.webfx.buildtool.modulefiles.SnapshotUsage;
import dev.webfx.lib.reusablestream.ReusableStream;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Bruno Salmon
 */
final public class ModuleRegistry {

    private final static Map<String /* module name */, LibraryModule> JDK_MODULES = new HashMap<>();

    static {
        // Registering JDK modules (packages registration will be done in the non-static constructor)
        new ResWebFxModuleFile("dev/webfx/buildtool/jdk/webfx.xml").getRequiredThirdPartyLibraryModules().forEach(m -> JDK_MODULES.put(m.getName(), m));
    }

    public static boolean isJdkModule(Module module) {
        return isJdkModule(module.getName());
    }

    public static boolean isJdkModule(String name) {
        return JDK_MODULES.containsKey(name);
    }

    private final Path workspaceDirectory;
    private final List<ProjectModule> registeredProjectModules = new ArrayList<>(); // those processed by the registration stream
    private int lastImportedProjectModuleIndex = -1; // index of last imported project in registeredProjectModules
    private int lastDeclaredProjectModuleIndex = -1; // index of last declared project in registeredProjectModules
    private final List<LibraryModule> registeredLibraryModules = new ArrayList<>(); // those processed by the registration stream
    private int lastDeclaredLibraryModuleIndex = -1; // index of last declared project in registeredLibraryModules
    private final List<Module> declaredModules = new ArrayList<>();
    private final Map<String /* module name */, DevProjectModule> devProjectModulesNameMap = new HashMap<>();
    private final Map<String /* module name */, M2ProjectModule> m2ProjectModulesNameMap = new HashMap<>();
    private final Map<String /* module name */, LibraryModule> libraryModulesNameMap = new HashMap<>();
    private final Map<Path, DevProjectModule> devProjectModulesPathMap = new HashMap<>();
    private final Map<String /* package name */, List<Module>> packagesModulesNameMap = new HashMap<>();

    private final ArrayDeque<ReusableStream<ProjectModule>> listOfRootModuleAndChildrenToRegister = new ArrayDeque<>();
    private final ReusableStream<ProjectModule> projectModuleRegistrationResume;
    private final ReusableStream<ProjectModule> projectModuleRegistrationStream;
    private final ReusableStream<Module> moduleRegistrationResume;
    private final ReusableStream<Module> moduleRegistrationStream;

    private final ReusableStream<Module> moduleDeclarationResume;
    private final ReusableStream<Module> moduleDeclarationStream;

    private final ReusableStream<M2ProjectModule> m2ProjectModulesWithExportSnapshotsResume;
    Map<String /* package or class name */, List<SnapshotUsage>> registeredSnapshotUsages = new HashMap<>(); // To be populated


        /***********************
         ***** Constructor *****
         ***********************/

    public ModuleRegistry(Path workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
        // Registering JDK packages
        JDK_MODULES.values().forEach(this::declareJdkModulePackages);
        // Stream for project modules registration
        projectModuleRegistrationResume = ReusableStream.resumeFromIterator(new Iterator<>() {
            ReusableStream<ProjectModule> rootModuleAndChildrenToRegisterResume;
            ProjectModule nextProjectModuleToRegister;

            @Override
            public boolean hasNext() {
                return getOrIncrementNextProjectModuleToRegister() != null;
            }

            @Override
            public ProjectModule next() {
                ProjectModule next = getOrIncrementNextProjectModuleToRegister();
                if (next != null) // No need to register (it's already done on creation), but we add the module to registeredProjectModules
                    registeredProjectModules.add(next);
                nextProjectModuleToRegister = null; // To force incrementation on next getOrIncrementNextProjectModuleToRegister() call
                return next;
            }

            private ProjectModule getOrIncrementNextProjectModuleToRegister() {
                while (nextProjectModuleToRegister == null) {
                    if (rootModuleAndChildrenToRegisterResume == null) { // Indicates that we need to go to the next module and children stream
                        // But what if there is no more ?
                        while (listOfRootModuleAndChildrenToRegister.isEmpty() && lastImportedProjectModuleIndex < registeredProjectModules.size() - 1) {
                            // Let's try to import more libraries
                            importProjectModuleRequiredLibraries(registeredProjectModules.get(++lastImportedProjectModuleIndex));
                        }
                        // Now let's try to pool the next module and children stream
                        rootModuleAndChildrenToRegisterResume = listOfRootModuleAndChildrenToRegister.poll();
                        // If we reached the end,
                        if (rootModuleAndChildrenToRegisterResume == null)
                            break; // we exit with next = null
                        // We got the next stream :) We need to call the resume operator, so we can take one element after the other
                        rootModuleAndChildrenToRegisterResume = rootModuleAndChildrenToRegisterResume.resume();
                    }
                    // Taking the next children from the current stream
                    nextProjectModuleToRegister = rootModuleAndChildrenToRegisterResume.findFirst().orElse(null);
                    if (nextProjectModuleToRegister == null) // null means that the stream has no more children,
                        rootModuleAndChildrenToRegisterResume = null; // This reset is to go to the next module and children stream on next loop
                }
                return nextProjectModuleToRegister;
            }
        });

        projectModuleRegistrationStream = replayProcessingResume(projectModuleRegistrationResume, registeredProjectModules);

        moduleRegistrationResume = projectModuleRegistrationResume.map(Module.class::cast).concat(registeredLibraryModules);

        moduleRegistrationStream = projectModuleRegistrationStream.map(Module.class::cast).concat(registeredLibraryModules);

        // Stream for project modules declaration
        moduleDeclarationResume =
                ReusableStream.resumeFromIterator(new Iterator<>() {
                    Module nextModuleToDeclare;

                    @Override
                    public boolean hasNext() {
                        return getOrIncrementNextProjectModuleToDeclare() != null;
                    }

                    @Override
                    public Module next() {
                        Module next = getOrIncrementNextProjectModuleToDeclare();
                        if (next instanceof ProjectModule)
                            declareProjectModulePackages((ProjectModule) next); // This also add the module to declaredModules
                        else if (next instanceof LibraryModule)
                            declareLibraryModulePackages((LibraryModule) next);
                        nextModuleToDeclare = null; // To force incrementation on next getOrIncrementNextProjectModuleToDeclare() call
                        return next;
                    }

                    private Module getOrIncrementNextProjectModuleToDeclare() {
                        while (nextModuleToDeclare == null) {
                            if (lastDeclaredLibraryModuleIndex < registeredLibraryModules.size() - 1)
                                nextModuleToDeclare = registeredLibraryModules.get(++lastDeclaredLibraryModuleIndex);
                            else if (lastDeclaredProjectModuleIndex < registeredProjectModules.size() - 1)
                                nextModuleToDeclare = registeredProjectModules.get(++lastDeclaredProjectModuleIndex);
                            else if (projectModuleRegistrationResume.findFirst().orElse(null) == null
                                    && lastDeclaredLibraryModuleIndex >= registeredLibraryModules.size() - 1
                                    && lastDeclaredProjectModuleIndex >= registeredProjectModules.size() - 1)
                                break;
                        }
                        return nextModuleToDeclare;
                    }
                });

        moduleDeclarationStream = replayProcessingResume(moduleDeclarationResume, declaredModules);

        m2ProjectModulesWithExportSnapshotsResume = projectModuleRegistrationStream
                // Keeping m2 projects only
                .filter(M2ProjectModule.class::isInstance).map(M2ProjectModule.class::cast)
                .filter(m2 -> m2.getName().equals(m2.getWebFxModuleFile().lookupExportedSnapshotFirstProjectName()))
                .map(this::registerExportSnapshot)
                .resume()
                ;
    }

    private M2ProjectModule registerExportSnapshot(M2ProjectModule m2) {
        m2.getWebFxModuleFile().javaPackagesFromExportSnapshotUsage()
                .forEach(javaPackageName -> {
                    List<SnapshotUsage> usages = registeredSnapshotUsages.computeIfAbsent(javaPackageName, k -> new ArrayList<>());
                    usages.add(new SnapshotUsage(javaPackageName, m2, m2.getWebFxModuleFile().modulesUsingJavaPackageFromExportSnapshot(javaPackageName).collect(Collectors.toList())));
                });
        m2.getWebFxModuleFile().javaClassesFromExportSnapshotUsage()
                .forEach(javaClassName -> {
                    List<SnapshotUsage> usages = registeredSnapshotUsages.computeIfAbsent(javaClassName, k -> new ArrayList<>());
                    usages.add(new SnapshotUsage(javaClassName, m2, m2.getWebFxModuleFile().modulesUsingJavaClassFromExportSnapshot(javaClassName).collect(Collectors.toList())));
                });
        return m2;
    }

    public Boolean askExportSnapshotsIfModuleIsUsingPackageOrClass(ProjectModule module, String packageOrClass) {
        List<SnapshotUsage> packageOrClassSnapshotUsages = registeredSnapshotUsages.get(packageOrClass);
        if (packageOrClassSnapshotUsages == null) {
            m2ProjectModulesWithExportSnapshotsResume.takeWhile(m2 -> registeredSnapshotUsages.get(packageOrClass) == null).findAny();
            packageOrClassSnapshotUsages = registeredSnapshotUsages.get(packageOrClass);
            if (packageOrClassSnapshotUsages == null)
                return null;
        }
        for (int i = 0, n = packageOrClassSnapshotUsages.size(); i < n; i++) {
            SnapshotUsage snapshotUsage = packageOrClassSnapshotUsages.get(i);
            Boolean moduleUsing = snapshotUsage.isModuleUsing(module);
            if (moduleUsing != null)
                return moduleUsing;
            if (i == n - 1) {
                final List<SnapshotUsage> finalUsages = packageOrClassSnapshotUsages;
                int finalSize = n;
                m2ProjectModulesWithExportSnapshotsResume.takeWhile(m2 -> finalUsages.size() == finalSize).findAny();
                n = packageOrClassSnapshotUsages.size();
            }
        }
        return null;
    }

    public ReusableStream<ProjectModule> getProjectModuleRegistrationResume() {
        return projectModuleRegistrationResume;
    }

    public ReusableStream<ProjectModule> getProjectModuleRegistrationStream() {
        return projectModuleRegistrationStream;
    }

    public ReusableStream<Module> getModuleRegistrationResume() {
        return moduleRegistrationResume;
    }

    public ReusableStream<Module> getModuleRegistrationStream() {
        return moduleRegistrationStream;
    }

    public ReusableStream<ProjectModule> getProjectModuleDeclarationResume() {
        return ProjectModule.filterProjectModules(moduleDeclarationResume);
    }

    public ReusableStream<ProjectModule> getProjectModuleDeclarationStream() {
        return ProjectModule.filterProjectModules(moduleDeclarationStream);
    }

    public ReusableStream<Module> getModuleDeclarationResume() {
        return moduleDeclarationResume;
    }

    public ReusableStream<Module> getModuleDeclarationStream() {
        return moduleDeclarationStream;
    }

    /********************************
     ***** Registration methods *****
     ********************************/

    private void declarePackageBelongsToModule(String packageName, Module module) {
        List<Module> lm = packagesModulesNameMap.get(packageName);
        if (lm == null) // First time we declare this package
            packagesModulesNameMap.put(packageName, lm = new ArrayList<>(1));
        else if (lm.contains(module)) // Already declared and with the same module (shouldn't arrive)
            return; // We just skip (but why this double declaration with same package and same module?)
        else {
            Module m = lm.get(0);
            String message = module + " and " + m + " share the same package " + packageName;
            DevProjectModule projectModule = module instanceof DevProjectModule ? (DevProjectModule) module : m instanceof DevProjectModule ? (DevProjectModule) m : null;
            DevRootModule workingModule = projectModule != null ? projectModule.getRootModule() : null;
            if (workingModule != null && (workingModule.isModuleUnderRootHomeDirectory(module) || workingModule.isModuleUnderRootHomeDirectory(m)))
                Logger.warning(message);
            else // Something happening in a library (not in the developer code)
                Logger.verbose(message);
            // Should always return, the exception is a hack to replace m = webfx-kit-gwt with module = webfx-kit-peers-extracontrols (they share the same package dev.webfx.extras.cell.collator.grid)
            //if (!(m instanceof ProjectModule) || ((ProjectModule) m).getTarget().isPlatformSupported(Platform.JRE))
            //    return;
        }
        lm.add(module);
    }

    void importProjectModuleRequiredLibraries(ProjectModule module) {
        module.getRequiredLibraryModules().forEach(this::importLibrary);
    }

    public void importLibrary(LibraryModule libraryModule) {
        String moduleName = libraryModule.getName();
        if (libraryModule.shouldBeDownloadedInM2()) {
            if (getRegisteredModuleOrLibraryWithExportedPackages(moduleName) == null)
                registerM2ProjectModule(new M2RootModule(libraryModule, this));
        } else if (getRegisteredLibrary(moduleName) == null) {
            //.out.println("Registering library " + moduleName);
            libraryModulesNameMap.put(moduleName, libraryModule);
            registeredLibraryModules.add(libraryModule);
        }
    }

    void declareProjectModulePackages(ProjectModule module) {
        //System.out.println("Declaring packages for project module " + module);
        module.getJavaSourcePackages().forEach(p -> declarePackageBelongsToModule(p, module));
        module.getWebFxModuleFile().getExplicitExportedPackages().forEach(p -> declarePackageBelongsToModule(p, module));
        declaredModules.add(module);
    }

    void declareLibraryModulePackages(LibraryModule module) {
        //System.out.println("Declaring packages for library " + module);
        module.getExportedPackages().forEach(p -> declarePackageBelongsToModule(p, module));
        declaredModules.add(module);
    }

    private void declareJdkModulePackages(LibraryModule module) {
        module.getExportedPackages().forEach(p -> declarePackageBelongsToModule(p, module));
    }

    Module getDeclaredJavaPackageModule(String packageName, ProjectModule sourceModule, boolean canReturnNull) {
        List<Module> lm = packagesModulesNameMap.get(packageName);
        Module module = lm == null ? null : lm.stream()
                .filter(m -> isSuitableModule(m, sourceModule))
                .findFirst()
                .orElse(null);
        if (module == null) { // Module not found :-(
            // Last chance: the package was actually in the source package! (ex: webfx-kit-extracontrols-registry-spi)
            if (sourceModule.getJavaSourcePackages().anyMatch(p -> p.equals(packageName)))
                module = sourceModule;
            else if (!canReturnNull) { // Otherwise, raising an exception (unless returning null is permitted)
                if (lm == null || lm.isEmpty())
                    throw new UnresolvedException("Unresolved module for package " + packageName + " (used by " + sourceModule + ")");
                StringBuilder sb = new StringBuilder("Unsuitable module for package " + packageName + " (used by " + sourceModule + ")");
                lm.forEach(m -> sb.append("\n").append(m.getName()).append(" declares this package, but it is not suitable because ").append(getUnsuitableModuleReason(m, sourceModule)));
                throw new UnresolvedException(sb.toString());
            }
        }
        return module;
    }

    private boolean isSuitableModule(Module m, ProjectModule sourceModule) {
        return getUnsuitableModuleReason(m, sourceModule) == null;
    }

    private String getUnsuitableModuleReason(Module m, ProjectModule sourceModule) {
        if (!(m instanceof ProjectModule))
            return null;
        ProjectModule pm = (ProjectModule) m;
        // First case: only executable source modules should include implementing interface modules (others should include the interface module instead)
        if (pm.isImplementingInterface() && !sourceModule.isExecutable()) {
            // Exception is however made for non-executable source modules that implement a provider
            // Ex: webfx-extras-controls-registry-openjfx can include webfx-extras-controls-registry-spi (which implements webfx-extras-controls-registry)
            boolean exception = sourceModule.getProvidedJavaServices()
                    .isEmpty() == false;
                  //.anyMatch(service -> pm.getJavaSourceFiles().anyMatch(c -> c.getClassName().equals(service)));
            if (!exception)
                return "it implements an interface module (" + pm.implementedInterfaces().findFirst().orElse(null) + "), and only executable modules should use it (" + sourceModule.getName() + " is not an executable module)";
        }
        // Second not permitted case:
        // Ex: webfx-extras-controls-registry-openjfx should not include webfx-extras-controls-registry (but webfx-extras-controls-registry-spi instead)
        if (pm.isInterface()) {
            if (sourceModule.getName().startsWith(pm.getName()))
                return "it should not be included in " + sourceModule.getName();
        }
        return null;
    }

    public Module getRegisteredModuleOrLibrary(String name) {
        Module module = getRegisteredModuleOrLibraryWithExportedPackages(name);
        if (module == null)
            module = getRegisteredLibrary(name);
        return module;
    }

    LibraryModule getRegisteredLibrary(String name) {
        LibraryModule module = JDK_MODULES.get(name);
        if (module == null)
            module = libraryModulesNameMap.get(name);
        return module;
    }

    public Module getRegisteredModuleOrLibraryWithExportedPackages(String name) {
        Module module = getRegisteredProjectModule(name);
        if (module == null)
            module = packagesModulesNameMap.values().stream().flatMap(Collection::stream).filter(m -> m.getName().equals(name)).findFirst().orElse(null);
        return module;
    }

    public ProjectModule getRegisteredProjectModule(String name) {
        ProjectModule module = getRegisteredDevProjectModule(name);
        if (module == null)
            module = getRegisteredM2ProjectModule(name);
        return module;
    }

    DevProjectModule getRegisteredDevProjectModule(String name) {
        return devProjectModulesNameMap.get(name);
    }

    DevProjectModule getRegisteredDevProjectModule(Path projectDirectory) {
        return devProjectModulesPathMap.get(projectDirectory);
    }

    public DevProjectModule getOrCreateDevProjectModule(Path projectDirectory) {
        DevProjectModule module = getRegisteredDevProjectModule(projectDirectory);
        if (module == null) {
            if (projectDirectory.equals(workspaceDirectory) || !projectDirectory.startsWith(workspaceDirectory))
                throw new BuildException("projectDirectory (" + projectDirectory + ") must be under workspace directory (" + workspaceDirectory + ")");
            Path parentDirectory = projectDirectory.getParent();
            DevProjectModule parentModule = parentDirectory.equals(workspaceDirectory) ? null : getOrCreateDevProjectModule(parentDirectory);
            module = createDevProjectModule(projectDirectory, parentModule);
        }
        return module;
    }

    DevProjectModule getOrCreateDevProjectModule(Path projectDirectory, DevProjectModule parentModule) {
        DevProjectModule module = getRegisteredDevProjectModule(projectDirectory);
        if (module == null)
            module = createDevProjectModule(projectDirectory, parentModule);
        return module;
    }

    private DevProjectModule createDevProjectModule(Path projectDirectory, DevProjectModule parentModule) {
        return registerDevProjectModule(
                parentModule != null && projectDirectory.startsWith(parentModule.getHomeDirectory()) ?
                        new DevProjectModule(projectDirectory, parentModule) :
                        new DevRootModule(projectDirectory, this)
        );
    }

    private DevProjectModule registerDevProjectModule(DevProjectModule module) {
        //System.out.println("Registering dev project " + module);
        devProjectModulesNameMap.put(module.getName(), module);
        devProjectModulesPathMap.put(module.getHomeDirectory(), module);
        if (module instanceof RootModule)
            addRootAndChildrenModulesInRegistrationStreamInput((RootModule) module);
        return module;
    }

    private void addRootAndChildrenModulesInRegistrationStreamInput(RootModule module) {
        listOfRootModuleAndChildrenToRegister.add(module.getThisAndChildrenModulesInDepth());
    }

    M2ProjectModule getRegisteredM2ProjectModule(String name) {
        return m2ProjectModulesNameMap.get(name);
    }

    public M2ProjectModule getOrCreateM2ProjectModule(String name, M2ProjectModule parentModule) {
        M2ProjectModule module = getRegisteredM2ProjectModule(name);
        if (module == null)
            module = createM2ProjectModule(name, parentModule);
        return module;
    }

    public M2ProjectModule createM2ProjectModule(String name, M2ProjectModule parentModule) {
        return registerM2ProjectModule(new M2ProjectModule(name, parentModule));
    }

    private M2ProjectModule registerM2ProjectModule(M2ProjectModule module) {
        //System.out.println("Registering M2 project " + module);
        m2ProjectModulesNameMap.put(module.getName(), module);
        if (module instanceof RootModule)
            addRootAndChildrenModulesInRegistrationStreamInput((RootModule) module);
        return module;
    }

    private static <T> ReusableStream<T> replayProcessingResume(ReusableStream<T> processingResume, List<? extends T> processedList) {
        return ReusableStream.fromIterable(() -> new Iterator<>() {

            int lastIndex = -1;
            T next;

            @Override
            public boolean hasNext() {
                return getOrIncrementNext() != null;
            }

            @Override
            public T next() {
                T next = getOrIncrementNext();
                this.next = null;
                return next;
            }

            private T getOrIncrementNext() {
                while (next == null) {
                    if (lastIndex < processedList.size() - 1)
                        next = processedList.get(++lastIndex);
                    else if (processingResume.findFirst().orElse(null) == null)
                        break;
                }
                return next;
            }
        });
    }
}
