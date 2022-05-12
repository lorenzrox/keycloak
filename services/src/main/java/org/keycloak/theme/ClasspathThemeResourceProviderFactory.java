package org.keycloak.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Properties;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class ClasspathThemeResourceProviderFactory implements ThemeResourceProviderFactory, ThemeResourceProvider {
    public static final String THEME_RESOURCES = "theme-resources";
    public static final String THEME_RESOURCES_PROPERTIES = THEME_RESOURCES + "/theme-resources.properties";
    public static final String THEME_RESOURCES_TEMPLATES = THEME_RESOURCES + "/templates/";
    public static final String THEME_RESOURCES_RESOURCES = THEME_RESOURCES + "/resources/";
    public static final String THEME_RESOURCES_MESSAGES = THEME_RESOURCES + "/messages/";

    private final String id;
    private final Properties properties;
    protected final ClassLoader classLoader;
 
    public ClasspathThemeResourceProviderFactory() {
        this("classpath", Thread.currentThread().getContextClassLoader());
    }

    public ClasspathThemeResourceProviderFactory(String id, ClassLoader classLoader) {
        this.id = id;
        this.classLoader = classLoader;
        this.properties = new Properties();

        try {
            InputStream in = classLoader.getResourceAsStream(THEME_RESOURCES_PROPERTIES);
            if (in != null) {
                Charset encoding = PropertiesUtil.detectEncoding(in);
                // detectEncoding closes the stream
                try (Reader reader = new InputStreamReader(classLoader.getResourceAsStream(THEME_RESOURCES_PROPERTIES),
                        encoding)) {
                    properties.load(reader);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load theme resources", e);
        }
    }

    @Override
    public ThemeResourceProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public URL getTemplate(String name) throws IOException {
        return classLoader.getResource(THEME_RESOURCES_TEMPLATES + name);
    }

    @Override
    public InputStream getResourceAsStream(String path) throws IOException {
        return getResourceAsStream(path, classLoader.getResource(THEME_RESOURCES_RESOURCES));
    }

    protected InputStream getResourceAsStream(String path, URL rootResourceURL) throws IOException {
        if (rootResourceURL == null) {
            return null;
        }
        final String rootPath = rootResourceURL.getPath();
        final URL resourceURL = classLoader.getResource(THEME_RESOURCES_RESOURCES + path);
        if(resourceURL == null || !resourceURL.getPath().startsWith(rootPath)) {
            return null;
        }
        else {
            return resourceURL.openConnection().getInputStream();
        }
    }

    @Override
    public Properties getMessages(String baseBundlename, Locale locale) throws IOException {
        Properties messages = new Properties();
        URL resource = classLoader.getResource(THEME_RESOURCES_MESSAGES + baseBundlename + "_" + locale.toString() + ".properties");
        loadMessages(messages, resource);
        return messages;
    }

    protected void loadMessages(Properties messages, URL resource) throws IOException {
        if (resource != null) {
            Charset encoding = PropertiesUtil.detectEncoding(resource.openStream());
            // detectEncoding closes the stream
            try (Reader reader = new InputStreamReader(resource.openStream(), encoding)) {
                messages.load(reader);
            }
        }
    }

    @Override
    public Properties getProperties() throws IOException {
        return properties;
    }

    @Override
    public boolean isThemeSupported(Theme theme) {
        String supportedThemes = properties.getProperty("supportedThemes");
        if (supportedThemes == null) {
            return true;
        }

        String[] values = supportedThemes.split(" ");
        for (String value : values) {
            String[] s = value.split("/");

            Theme.Type type = Theme.Type.valueOf(s[0].toUpperCase());
            if (type != theme.getType()) {
                continue;
            }

            if (s.length < 2 || theme.getName().equals(s[1])) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

}
