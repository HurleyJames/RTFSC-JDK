/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.loader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import jdk.internal.misc.JavaLangAccess;
import jdk.internal.misc.SharedSecrets;
import jdk.internal.module.Modules;
import jdk.internal.module.ServicesCatalog;
import jdk.internal.util.StaticProperty;

/**
 * Find resources and packages in modules defined to the boot class loader or
 * resources and packages on the "boot class path" specified via -Xbootclasspath/a:
 */
// 可以看做是BootClassLoader的代理
public class BootLoader {
    private BootLoader() { }
    
    // The unnamed module for the boot loader
    private static final Module UNNAMED_MODULE; // BootLoader对应的未命名模块
    
    // ServiceCatalog for the boot class loader
    private static final ServicesCatalog SERVICES_CATALOG = ServicesCatalog.create();
    
    // ClassLoaderValue map for the boot class loader
    private static final ConcurrentHashMap<?, ?> CLASS_LOADER_VALUE_MAP = new ConcurrentHashMap<>();
    
    private static final String JAVA_HOME = StaticProperty.javaHome();
    
    
    static {
        UNNAMED_MODULE = SharedSecrets.getJavaLangAccess().defineUnnamedModule(null);
        setBootLoaderUnnamedModule0(UNNAMED_MODULE);
    }
    
    
    /**
     * Returns the unnamed module for the boot loader.
     */
    // 获取bootstrap类加载器的未命名模块
    public static Module getUnnamedModule() {
        return UNNAMED_MODULE;
    }
    
    /**
     * Returns the ServiceCatalog for modules defined to the boot class loader.
     */
    // 获取bootstrap类加载器可以加载到的服务目录
    public static ServicesCatalog getServicesCatalog() {
        return SERVICES_CATALOG;
    }
    
    /**
     * Returns the ClassLoaderValue map for the boot class loader.
     */
    // 返回bootstrap类加载器的类加载器局部缓存
    public static ConcurrentHashMap<?, ?> getClassLoaderValueMap() {
        return CLASS_LOADER_VALUE_MAP;
    }
    
    /**
     * Returns {@code true} if there is a class path associated with the
     * BootLoader.
     */
    // 判断bootstrap类加载器是否关联了类路径
    public static boolean hasClassPath() {
        return ClassLoaders.bootLoader().hasClassPath();
    }
    
    /**
     * Registers a module with this class loader so that its classes
     * (and resources) become visible via this class loader.
     */
    // 将加载的模块信息注册到bootstrap类加载器中
    public static void loadModule(ModuleReference mref) {
        ClassLoaders.bootLoader().loadModule(mref);
    }
    
    /**
     * Loads the Class object with the given name defined to the boot loader.
     */
    // 通过bootstrap类加载器查找类是否已加载，如果找不到则返回null
    public static Class<?> loadClassOrNull(String name) {
        BuiltinClassLoader bootLoader = ClassLoaders.bootLoader();
        
        return bootLoader.loadClassOrNull(name);
    }
    
    /**
     * Loads the Class object with the given name in the given module defined to the boot loader.
     * Returns {@code null} if not found.
     */
    // 通过bootstrap类加载器查找类是否已加载，如果找不到则返回null
    public static Class<?> loadClass(Module module, String name) {
        Class<?> c = loadClassOrNull(name);
    
        if(c != null && c.getModule() == module) {
            return c;
        } else {
            return null;
        }
    }
    
    /**
     * Returns a URL to a resource in a module defined to the boot loader.
     */
    /*
     * 在指定的模块路径或bootstrap类加载器的类路径下查找匹配的资源
     *
     * 如果moduleName为null，则在当前类加载器关联的类路径(根目录)下查找首个匹配的资源
     * 如果moduleName不为null，则在模块路径(根目录)下查找首个匹配的资源
     */
    public static URL findResource(String moduleName, String name) throws IOException {
        return ClassLoaders.bootLoader().findResource(moduleName, name);
    }
    
    /**
     * Returns an input stream to a resource in a module defined to the boot loader.
     */
    // 在指定的模块路径(根目录)或bootstrap类加载器关联的类路径(根目录)下查找匹配的资源；如果成功找到资源，则返回其入流
    public static InputStream findResourceAsStream(String mn, String name) throws IOException {
        return ClassLoaders.bootLoader().findResourceAsStream(mn, name);
    }
    
    /**
     * Returns the URL to the given resource in any of the modules
     * defined to the boot loader and the boot class path.
     */
    // 在bootstrap类加载器可以访问到的模块路径/类路径下搜索首个匹配的资源（遍历所有可能的位置，一发现匹配资源就返回）
    public static URL findResource(String name) {
        return ClassLoaders.bootLoader().findResource(name);
    }
    
    /**
     * Returns an Iterator to iterate over the resources of the given name
     * in any of the modules defined to the boot loader.
     */
    // 在bootstrap类加载器下辖的模块路径/类路径的根目录下搜索所有匹配的资源
    public static Enumeration<URL> findResources(String name) throws IOException {
        return ClassLoaders.bootLoader().findResources(name);
    }
    
    /**
     * Define a package for the given class to the boot loader, if not already defined.
     */
    // 获取指定类所在包的Package对象
    public static Package definePackage(Class<?> c) {
        return getDefinedPackage(c.getPackageName());
    }
    
    /**
     * Returns the Package of the given name defined to the boot loader or null
     * if the package has not been defined.
     */
    // 获取指定包名的Package对象，如果该包还未被加载过，则返回null
    public static Package getDefinedPackage(String pn) {
        Package pkg = ClassLoaders.bootLoader().getDefinedPackage(pn);
        if (pkg == null) {
            String location = getSystemPackageLocation(pn.replace('.', '/'));
            if (location != null) {
                pkg = PackageHelper.definePackage(pn.intern(), location);
            }
        }
        return pkg;
    }
    
    /**
     * Returns a stream of the packages defined to the boot loader.
     */
    // 将bootstrap类加载器定义的包名封装到流中返回
    public static Stream<Package> packages() {
        return Arrays.stream(getSystemPackageNames()).map(name -> getDefinedPackage(name.replace('/', '.')));
    }
    
    /**
     * Returns an array of the binary name of the packages defined by
     * the boot loader, in VM internal form (forward slashes instead of dot).
     */
    private static native String[] getSystemPackageNames();
    
    /**
     * Returns the location of the package of the given name, if defined by the boot loader; otherwise {@code null} is returned.
     *
     * The location may be a module from the runtime image or exploded image,
     * or from the boot class append path (i.e. -Xbootclasspath/a or BOOT-CLASS-PATH attribute specified in java agent).
     */
    private static native String getSystemPackageLocation(String name);
    
    private static native void setBootLoaderUnnamedModule0(Module module);
    
    
    /**
     * Helper class to define {@code Package} objects for packages in modules
     * defined to the boot loader.
     */
    static class PackageHelper {
        private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
        
        /**
         * Define the {@code Package} with the given name. The specified
         * location is a jrt URL to a named module in the run-time image,
         * a file URL to a module in an exploded run-time image, or a file
         * path to an entry on the boot class path (java agent Boot-Class-Path
         * or -Xbootclasspath/a.
         *
         * <p> If the given location is a JAR file containing a manifest,
         * the defined Package contains the versioning information from
         * the manifest, if present.
         *
         * @param name     package name
         * @param location location where the package is (jrt URL or file URL
         *                 for a named module in the run-time or exploded image;
         *                 a file path for a package from -Xbootclasspath/a)
         */
        static Package definePackage(String name, String location) {
            Module module = findModule(location);
            if (module != null) {
                // named module from runtime image or exploded module
                if (name.isEmpty())
                    throw new InternalError("empty package in " + location);
                return JLA.definePackage(ClassLoaders.bootLoader(), name, module);
            }
            
            // package in unnamed module (-Xbootclasspath/a)
            URL url = toFileURL(location);
            Manifest man = url != null ? getManifest(location) : null;
            
            return ClassLoaders.bootLoader().defineOrCheckPackage(name, man, url);
        }
        
        /**
         * Finds the module at the given location defined to the boot loader.
         * The module is either in runtime image or exploded image.
         * Otherwise this method returns null.
         */
        private static Module findModule(String location) {
            String mn = null;
            if (location.startsWith("jrt:/")) {
                // named module in runtime image ("jrt:/".length() == 5)
                mn = location.substring(5, location.length());
            } else if (location.startsWith("file:/")) {
                // named module in exploded image
                Path path = Path.of(URI.create(location));
                Path modulesDir = Path.of(JAVA_HOME, "modules");
                if (path.startsWith(modulesDir)) {
                    mn = path.getFileName().toString();
                }
            }
            
            // return the Module object for the module name. The Module may
            // in the boot layer or a child layer for the case that the module
            // is loaded into a running VM
            if (mn != null) {
                String name = mn;
                return Modules.findLoadedModule(mn).orElseThrow(() -> new InternalError(name + " not loaded"));
            } else {
                return null;
            }
        }
        
        /**
         * Returns URL if the given location is a regular file path.
         */
        private static URL toFileURL(String location) {
            return AccessController.doPrivileged(new PrivilegedAction<>() {
                public URL run() {
                    Path path = Path.of(location);
                    if (Files.isRegularFile(path)) {
                        try {
                            return path.toUri().toURL();
                        } catch (MalformedURLException e) {}
                    }
                    return null;
                }
            });
        }
        
        /**
         * Returns the Manifest if the given location is a JAR file
         * containing a manifest.
         */
        private static Manifest getManifest(String location) {
            return AccessController.doPrivileged(new PrivilegedAction<>() {
                public Manifest run() {
                    Path jar = Path.of(location);
                    try (InputStream in = Files.newInputStream(jar);
                         JarInputStream jis = new JarInputStream(in, false)) {
                        return jis.getManifest();
                    } catch (IOException e) {
                        return null;
                    }
                }
            });
        }
    }
    
}