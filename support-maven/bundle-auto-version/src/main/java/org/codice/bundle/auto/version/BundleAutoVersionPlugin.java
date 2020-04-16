/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General private License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General private License for more details. A copy of the GNU Lesser General private
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.bundle.auto.version;

import com.google.common.base.Strings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

@Mojo(
    name = "bundle-auto-version",
    defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
    threadSafe = true)
public class BundleAutoVersionPlugin extends AbstractMojo {

  private static final String COPYRIGHT_NOTICE =
      "<!--\n"
          + "/**\n"
          + " * Copyright (c) Codice Foundation\n"
          + " *\n"
          + " * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either\n"
          + " * version 3 of the License, or any later version.\n"
          + " *\n"
          + " * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.\n"
          + " * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at\n"
          + " * <http://www.gnu.org/licenses/lgpl.html>.\n"
          + " *\n"
          + " **/\n"
          + " -->";

  private static final String PACKAGE_IMPORT_DELIMETER = "\",";

  private static final String IMPORT_PACKAGE_PROP = "Import-Package";
  private static final String MAVEN_BUNDLE_PLUGIN_ARTIFACT_ID = "maven-bundle-plugin";
  private static final String MAVEN_CONFIG_INSTRUCTIONS = "instructions";

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private MavenProject mavenProject;

  @Override
  public void execute() {
    String packageImports = getManifestPackageImports(mavenProject);

    if (Strings.isNullOrEmpty(packageImports)) {
      getLog().warn("No 'MANIFEST.MF' was found in build output, skipping");

      return;
    }

    Model model =
        Optional.ofNullable(readProjectModel(mavenProject))
            .orElseThrow(() -> new RuntimeException("Unable to read project model from pom.xml"));

    try (FileReader pomFileReader = new FileReader(mavenProject.getFile())) {
      model = new MavenXpp3Reader().read(pomFileReader);
    } catch (IOException | XmlPullParserException e) {
      getLog().error("Error parsing model for Maven project", e);
    }

    Plugin mavenBundlePlugin = getMavenBundlePlugin(model.getBuild().getPlugins());

    if (mavenBundlePlugin == null)
      throw new RuntimeException("Unable to find " + mavenBundlePlugin);

    Xpp3Dom configInstructions = getPluginConfiguration(mavenBundlePlugin);

    if (configInstructions == null)
      throw new RuntimeException("Unable to locate configuration for " + mavenBundlePlugin);

    if (haveSamePackageImports(
        packageImports, configInstructions.getChild(IMPORT_PACKAGE_PROP).getValue())) {
      getLog().info("Package imports between pom.xml and MANIFEST.MF match, skipping");
      return;
    }

    configInstructions.getChild(IMPORT_PACKAGE_PROP).setValue(packageImports);

    saveProjectModel(mavenProject, model);
  }

  private boolean haveSamePackageImports(String manifestPackageImports, String pomPackageImports) {
    List<String> manifestEntries = toList(manifestPackageImports);
    List<String> pomEntries = toList(pomPackageImports);

    return pomEntries.equals(manifestEntries);
  }

  private List<String> toList(String packageImport) {
    return Arrays.stream(packageImport.split(PACKAGE_IMPORT_DELIMETER))
        .sorted()
        .map(String::trim)
        .collect(Collectors.toList());
  }

  private void saveProjectModel(MavenProject project, Model model) {
    try (FileWriter pomFileWriter = new FileWriter(new File(getProjectPomPath(project).toUri()))) {
      new MavenXpp3Writer().write(pomFileWriter, model);
      addCopyrightNotice(project);
    } catch (IOException e) {
      getLog().error("Error saving project model to pom file", e);
    }
  }

  private void addCopyrightNotice(MavenProject project) throws IOException {
    List<String> pomFileLines =
        Files.lines(getProjectPomPath(project)).collect(Collectors.toList());

    pomFileLines.set(0, appendCopyrightNotice(pomFileLines.get(0)));

    Files.write(getProjectPomPath(project), pomFileLines, Charset.forName("UTF-8"));
  }

  private String appendCopyrightNotice(String line) {
    return line + "\n" + COPYRIGHT_NOTICE;
  }

  private Model readProjectModel(MavenProject project) {
    try (FileReader pomFileReader = new FileReader(project.getFile())) {
      return new MavenXpp3Reader().read(pomFileReader);
    } catch (IOException | XmlPullParserException e) {
      getLog().error("Error reading project model from pom file", e);
    }

    return null;
  }

  private Path getProjectPomPath(MavenProject project) {
    return Paths.get(project.getBasedir() + "/pom.xml");
  }

  private String getManifestPackageImports(MavenProject project) {
    Path manifestFilePath =
        Paths.get(project.getBuild().getOutputDirectory() + "/META-INF/MANIFEST.MF");

    if (!manifestFilePath.toFile().exists()) return "";

    Manifest manifest = null;

    try (FileInputStream manifestInputStream = new FileInputStream(manifestFilePath.toString())) {
      manifest = new Manifest(manifestInputStream);
    } catch (IOException e) {
      getLog().error("Error reading 'MANIFEST.MF' for the project", e);
    }

    if (manifest == null) throw new RuntimeException("Unable to locate generated 'MANIFEST.MF'");

    return Arrays.stream(
            manifest
                .getMainAttributes()
                .getValue(IMPORT_PACKAGE_PROP)
                .split(PACKAGE_IMPORT_DELIMETER))
        .sorted()
        .collect(Collectors.joining("\",\n"));
  }

  private Plugin getMavenBundlePlugin(List<Plugin> buildPlugins) {
    return buildPlugins.stream()
        .filter(plugin -> MAVEN_BUNDLE_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId()))
        .findFirst()
        .orElse(null);
  }

  private Xpp3Dom getPluginConfiguration(Plugin plugin) {
    Xpp3Dom configuration =
        Optional.ofNullable(plugin.getConfiguration()).map(Xpp3Dom.class::cast).orElse(null);

    return Arrays.stream(configuration.getChildren())
        .filter(entry -> MAVEN_CONFIG_INSTRUCTIONS.equals(entry.getName()))
        .findFirst()
        .orElse(null);
  }
}
