/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.test.blueprint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarInputStream;

import de.kalpatec.pojosr.framework.PojoServiceRegistryFactoryImpl;
import de.kalpatec.pojosr.framework.launch.BundleDescriptor;
import de.kalpatec.pojosr.framework.launch.ClasspathScanner;
import de.kalpatec.pojosr.framework.launch.PojoServiceRegistry;
import de.kalpatec.pojosr.framework.launch.PojoServiceRegistryFactory;
import org.apache.camel.impl.DefaultClassResolver;
import org.apache.camel.spi.ClassResolver;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.ResourceHelper;
import org.ops4j.pax.swissbox.tinybundles.core.TinyBundle;
import org.ops4j.pax.swissbox.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.test.junit4.TestSupport.createDirectory;
import static org.apache.camel.test.junit4.TestSupport.deleteDirectory;

/**
 * Helper for using Blueprint with Camel.
 */
public final class CamelBlueprintHelper {

    public static final long DEFAULT_TIMEOUT = 30000;
    public static final String BUNDLE_FILTER = "(Bundle-SymbolicName=*)";
    public static final String BUNDLE_VERSION = "1.0.0";
    private static final transient Logger LOG = LoggerFactory.getLogger(CamelBlueprintHelper.class);
    private static final ClassResolver RESOLVER = new DefaultClassResolver();

    private CamelBlueprintHelper() {
    }

    public static BundleContext createBundleContext(String name, String descriptors, boolean includeTestBundle) throws Exception {
        return createBundleContext(name, descriptors, includeTestBundle, BUNDLE_FILTER, BUNDLE_VERSION);
    }

    public static BundleContext createBundleContext(String name, String descriptors, boolean includeTestBundle,
                                                    String bundleFilter, String testBundleVersion) throws Exception {
        TinyBundle bundle = null;

        if (includeTestBundle) {
            // add ourselves as a bundle
            bundle = createTestBundle(name, testBundleVersion, descriptors);
        }

        return createBundleContext(name, bundleFilter, bundle);
    }

    public static BundleContext createBundleContext(String name, String bundleFilter, TinyBundle bundle) throws Exception {
        // ensure pojosr stores bundles in an unique target directory
        String uid = "" + System.currentTimeMillis();
        String tempDir = "target/bundles/" + uid;
        System.setProperty("org.osgi.framework.storage", tempDir);
        createDirectory(tempDir);

        // use another directory for the jar of the bundle as it cannot be in the same directory
        // as it has a file lock during running the tests which will cause the temp dir to not be
        // fully deleted between tests
        createDirectory("target/test-bundles");

        // get the bundles
        List<BundleDescriptor> bundles = getBundleDescriptors(bundleFilter);

        if (bundle != null) {
            String jarName = name.toLowerCase(Locale.ENGLISH) + "-" + uid + ".jar";
            bundles.add(getBundleDescriptor("target/test-bundles/" + jarName, bundle));
        }

        if (LOG.isDebugEnabled()) {
            for (int i = 0; i < bundles.size(); i++) {
                BundleDescriptor desc = bundles.get(i);
                LOG.debug("Bundle #{} -> {}", i, desc);
            }
        }

        // setup pojosr to use our bundles
        Map<String, List<BundleDescriptor>> config = new HashMap<String, List<BundleDescriptor>>();
        config.put(PojoServiceRegistryFactory.BUNDLE_DESCRIPTORS, bundles);

        // create pojorsr osgi service registry
        PojoServiceRegistry reg = new PojoServiceRegistryFactoryImpl().newPojoServiceRegistry(config);
        return reg.getBundleContext();
    }

    public static void disposeBundleContext(BundleContext bundleContext) throws BundleException {
        try {
            if (bundleContext != null) {
                List<Bundle> bundles = new ArrayList<Bundle>();
                bundles.addAll(Arrays.asList(bundleContext.getBundles()));
                Collections.reverse(bundles);
                for (Bundle bundle : bundles) {
                    LOG.debug("Stopping bundle {}", bundle);
                    bundle.stop();
                }
            }
        } catch (Exception e) {
            LOG.warn("Error during disposing BundleContext. This exception will be ignored.", e);
        } finally {
            String tempDir = System.clearProperty("org.osgi.framework.storage");
            if (tempDir != null) {
                LOG.info("Deleting work directory {}", tempDir);
                deleteDirectory(tempDir);
            }
        }
    }

    public static <T> T getOsgiService(BundleContext bundleContext, Class<T> type, long timeout) {
        return getOsgiService(bundleContext, type, null, timeout);
    }

    public static <T> T getOsgiService(BundleContext bundleContext, Class<T> type) {
        return getOsgiService(bundleContext, type, null, DEFAULT_TIMEOUT);
    }

    public static <T> T getOsgiService(BundleContext bundleContext, Class<T> type, String filter) {
        return getOsgiService(bundleContext, type, filter, DEFAULT_TIMEOUT);
    }

    public static <T> T getOsgiService(BundleContext bundleContext, Class<T> type, String filter, long timeout) {
        ServiceTracker tracker = null;
        try {
            String flt;
            if (filter != null) {
                if (filter.startsWith("(")) {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")" + filter + ")";
                } else {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")(" + filter + "))";
                }
            } else {
                flt = "(" + Constants.OBJECTCLASS + "=" + type.getName() + ")";
            }
            Filter osgiFilter = FrameworkUtil.createFilter(flt);
            tracker = new ServiceTracker(bundleContext, osgiFilter, null);
            tracker.open(true);
            // Note that the tracker is not closed to keep the reference
            // This is buggy, as the service reference may change i think
            Object svc = tracker.waitForService(timeout);
            if (svc == null) {
                Dictionary<?, ?> dic = bundleContext.getBundle().getHeaders();
                System.err.println("Test bundle headers: " + explode(dic));

                for (ServiceReference ref : asCollection(bundleContext.getAllServiceReferences(null, null))) {
                    System.err.println("ServiceReference: " + ref + ", bundle: " + ref.getBundle() + ", symbolicName: " + ref.getBundle().getSymbolicName());
                }

                for (ServiceReference ref : asCollection(bundleContext.getAllServiceReferences(null, flt))) {
                    System.err.println("Filtered ServiceReference: " + ref + ", bundle: " + ref.getBundle() + ", symbolicName: " + ref.getBundle().getSymbolicName());
                }

                throw new RuntimeException("Gave up waiting for service " + flt);
            }
            return type.cast(svc);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Invalid filter", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    protected static TinyBundle createTestBundle(String name, String version, String descriptors) throws FileNotFoundException, MalformedURLException {
        TinyBundle bundle = TinyBundles.newBundle();
        for (URL url : getBlueprintDescriptors(descriptors)) {
            LOG.info("Using Blueprint XML file: " + url.getFile());
            bundle.add("OSGI-INF/blueprint/blueprint-" + url.getFile().replace("/", "-"), url);
        }
        bundle.set("Manifest-Version", "2")
                .set("Bundle-ManifestVersion", "2")
                .set("Bundle-SymbolicName", name)
                .set("Bundle-Version", version);
        return bundle;
    }

    /**
     * Explode the dictionary into a <code>,</code> delimited list of <code>key=value</code> pairs.
     */
    private static String explode(Dictionary<?, ?> dictionary) {
        Enumeration<?> keys = dictionary.keys();
        StringBuilder result = new StringBuilder();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            result.append(String.format("%s=%s", key, dictionary.get(key)));
            if (keys.hasMoreElements()) {
                result.append(", ");
            }
        }
        return result.toString();
    }

    /**
     * Provides an iterable collection of references, even if the original array is <code>null</code>.
     */
    private static Collection<ServiceReference> asCollection(ServiceReference[] references) {
        return references  == null ? new ArrayList<ServiceReference>(0) : Arrays.asList(references);
    }

    /**
     * Gets list of bundle descriptors.
     * @param bundleFilter Filter expression for OSGI bundles.
     *
     * @return List pointers to OSGi bundles.
     * @throws Exception If looking up the bundles fails.
     */
    private static List<BundleDescriptor> getBundleDescriptors(final String bundleFilter) throws Exception {
        return new ClasspathScanner().scanForBundles(bundleFilter);
    }

    /**
     * Gets the bundle descriptors as {@link URL} resources.
     *
     * @param descriptors the bundle descriptors, can be separated by comma
     * @return the bundle descriptors.
     * @throws FileNotFoundException is thrown if a bundle descriptor cannot be found
     */
    private static Collection<URL> getBlueprintDescriptors(String descriptors) throws FileNotFoundException, MalformedURLException {
        List<URL> answer = new ArrayList<URL>();
        String descriptor = descriptors;
        if (descriptor != null) {
            // there may be more resources separated by comma
            Iterator<Object> it = ObjectHelper.createIterator(descriptor);
            while (it.hasNext()) {
                String s = (String) it.next();
                LOG.trace("Resource descriptor: {}", s);

                // remove leading / to be able to load resource from the classpath
                s = FileUtil.stripLeadingSeparator(s);

                // if there is wildcards for *.xml then we need to find the urls from the package
                if (s.endsWith("*.xml")) {
                    String packageName = s.substring(0, s.length() - 5);
                    // remove trailing / to be able to load resource from the classpath
                    Enumeration<URL> urls = ObjectHelper.loadResourcesAsURL(packageName);
                    while (urls.hasMoreElements()) {
                        URL url = urls.nextElement();
                        File dir = new File(url.getFile());
                        if (dir.isDirectory()) {
                            File[] files = dir.listFiles();
                            if (files != null) {
                                for (File file : files) {
                                    if (file.isFile() && file.exists() && file.getName().endsWith(".xml")) {
                                        String name = packageName + file.getName();
                                        LOG.debug("Resolving resource: {}", name);
                                        URL xmlUrl = ObjectHelper.loadResourceAsURL(name);
                                        if (xmlUrl != null) {
                                            answer.add(xmlUrl);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LOG.debug("Resolving resource: {}", s);
                    URL url = ResourceHelper.resolveMandatoryResourceAsUrl(RESOLVER, s);
                    if (url == null) {
                        throw new FileNotFoundException("Resource " + s + " not found");
                    }
                    answer.add(url);
                }
            }
        } else {
            throw new IllegalArgumentException("No bundle descriptor configured. Override getBlueprintDescriptor() or getBlueprintDescriptors() method");
        }

        if (answer.isEmpty()) {
            throw new IllegalArgumentException("Cannot find any resources in classpath from descriptor " + descriptors);
        }
        return answer;
    }

    private static BundleDescriptor getBundleDescriptor(String path, TinyBundle bundle) throws Exception {
        File file = new File(path);
        // tell the JVM its okay to delete this file on exit as its a temporary file
        // the JVM may not successfully delete the file though
        file.deleteOnExit();

        FileOutputStream fos = new FileOutputStream(file, false);
        InputStream is = bundle.build();
        try {
            IOHelper.copyAndCloseInput(is, fos);
        } finally {
            IOHelper.close(is, fos);
        }

        BundleDescriptor answer = null;
        FileInputStream fis = null;
        JarInputStream jis = null;
        try {
            fis = new FileInputStream(file);
            jis = new JarInputStream(fis);
            Map<String, String> headers = new HashMap<String, String>();
            for (Map.Entry<Object, Object> entry : jis.getManifest().getMainAttributes().entrySet()) {
                headers.put(entry.getKey().toString(), entry.getValue().toString());
            }

            answer = new BundleDescriptor(
                    bundle.getClass().getClassLoader(),
                    new URL("jar:" + file.toURI().toString() + "!/"),
                    headers);
        } finally {
            IOHelper.close(jis, fis);
        }

        return answer;
    }

}
