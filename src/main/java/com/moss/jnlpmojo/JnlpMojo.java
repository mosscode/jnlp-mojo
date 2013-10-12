/**
 * Copyright (C) 2013, Moss Computing Inc.
 *
 * This file is part of jnlp-mojo.
 *
 * jnlp-mojo is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * jnlp-mojo is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with jnlp-mojo; see the file COPYING.  If not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 *
 * Linking this library statically or dynamically with other modules is
 * making a combined work based on this library.  Thus, the terms and
 * conditions of the GNU General Public License cover the whole
 * combination.
 *
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent
 * modules, and to copy and distribute the resulting executable under
 * terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that
 * module.  An independent module is a module which is not derived from
 * or based on this library.  If you modify this library, you may extend
 * this exception to your version of the library, but you are not
 * obligated to do so.  If you do not wish to do so, delete this
 * exception statement from your version.
 */
package com.moss.jnlpmojo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * @phase process-classes
 * @goal generate
 */
public class JnlpMojo extends AbstractMojo {

	/** @component */
	private ArtifactFactory artifactFactory;

	/** @component */
	private ArtifactResolver resolver;

	/** @component */
	private ArtifactMetadataSource artifactMetadataSource;

	/** @parameter expression="${project}" */
	private MavenProject project;

	/**@parameter expression="${localRepository}" */
	private ArtifactRepository localRepository;

	/** @parameter expression="${project.runtimeClasspathElements}" */
	private List<String> classpathElements;

	/** @parameter expression="${project.remoteArtifactRepositories}" */
	private List<ArtifactRepository> remoteRepositories;

	/** @parameter */
	private List<String> interfaces;
	
	/**@parameter expression="${project.build.directory}" */
	private File buildDir;
	
	/** @parameter */
	private String[] templates = new String[]{};
	
	/** @parameter expression="${mainClass}" */
	private String mainClass;
	
	/** @parameter expression="${codeBase}" */
	private String codeBase;
	
	/**
	 * Regex exclusion pattern (against groupId:artifactId)
	 *  
	 * @parameter 
	 */
	private String exclusionPattern;
	
	private byte[] copyBuffer = new byte[1024*1024]; //1mb buffer
	private void copy(File from, File to) throws IOException {
		System.out.println("Copying from " + from.getAbsolutePath() + " to " + to.getAbsolutePath());
		FileInputStream in = new FileInputStream(from);
		FileOutputStream out = new FileOutputStream(to);
		
		for(int numRead = in.read(copyBuffer); numRead!=-1; numRead = in.read(copyBuffer)){
			out.write(copyBuffer, 0, numRead);
		}
		in.close();
		out.close();
	}
	private void copy(InputStream in, File to) throws IOException {
		FileOutputStream out = new FileOutputStream(to);
		
		for(int numRead = in.read(copyBuffer); numRead!=-1; numRead = in.read(copyBuffer)){
			out.write(copyBuffer, 0, numRead);
		}
		out.close();
	}
	public void writeToFile(String text, File file){
		try {
			Writer writer = new FileWriter(file);
			writer.write(text);
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	private String readResourceAsString(String resourceName) throws IOException {
		InputStream in = getClass().getResourceAsStream(resourceName);
		String string = readStreamAsString(in);
		in.close();
		return string;
	}
	private String readFileAsString(File file) throws IOException {
		FileInputStream in = new FileInputStream(file);
		String string = readStreamAsString(in);
		in.close();
		return string;
	}
	
	private String readStreamAsString(InputStream in) throws IOException {
		InputStreamReader reader = new InputStreamReader(in);
		char[] buffer = new char[1024];
		StringBuffer out = new StringBuffer();
		for(int numRead = reader.read(buffer); numRead!=-1; numRead = reader.read(buffer)){
			out.append(buffer, 0, numRead);
		}
		return out.toString();
	}
	
	private void writeJnlpFile(String template, File jnlpFile, StringBuffer jarsText){
		if(jnlpFile.exists() && !jnlpFile.delete())
			throw new RuntimeException("Could not delete " + jnlpFile.getAbsolutePath());
		
		
		String jnlp = template
			.replaceAll("TITLE", project.getName())
			.replaceAll("VENDOR", project.getGroupId())
			.replaceAll("DESCRIPTION", project.getDescription())
			.replaceAll("MAIN_CLASS", mainClass)
			.replaceAll("CODEBASE", codeBase)
			.replaceAll("HREF", codeBase + jnlpFile.getName())
			.replaceAll("JARS", jarsText.toString());
		
		System.out.println("Writing jnlp file " + jnlpFile.getAbsolutePath());
		writeToFile(jnlp, jnlpFile);
		System.out.println(jnlp);
	}
	
	/*
	 * <j2se version="1.6.0_06" initial-heap-size="256m" max-heap-size="512m" href="http://java.sun.com/products/autodl/j2se"/>
	 * <jar href="saturn-pos-client-0.0.1-SNAPSHOT.jar"/>
	 */
	public void execute() throws MojoExecutionException, MojoFailureException {
		try {
			File dir = new File(buildDir, "/jnlp-template");
			if(!dir.exists() && !dir.mkdirs()){
				throw new RuntimeException("Could not create directory:" + dir.getAbsolutePath());
			}
			System.out.println("Using " + dir.getAbsolutePath());
			
			StringBuffer jars = new StringBuffer();
			Set<Artifact> dependencies = dependencies();
			
			File thisJar = new File(buildDir, project.getArtifactId() + "-" + project.getVersion() + ".jar");
			File thisDest = new File(dir, thisJar.getName());
			jars.append("		<jar href=\"" + thisDest.getName() + "\"/>\n");
			copy(thisJar, thisDest);
			
			Pattern p;
			if (exclusionPattern != null) {
				p = Pattern.compile(exclusionPattern);
			}
			else {
				p = null;
			}
			
			for (Artifact dep : dependencies) {
				
				if (p != null) {
					String input = dep.getGroupId() + ":" + dep.getArtifactId();
					Matcher m = p.matcher(input);
					if (m.matches()) {
						
						if (getLog().isInfoEnabled()) {
							getLog().info("Skipping artifact excluded by exclusionPattern " + exclusionPattern + ": " + input);
						}
						
						continue;
					}
				}
				
				File sourceFile = dep.getFile();
				File destFile = new File(dir, sourceFile.getName());
				jars.append("		<jar href=\"" + destFile.getName() + "\"/>\n");
				
				if(destFile.exists() && destFile.lastModified()!=sourceFile.lastModified()){
					System.out.println("Deleting " + destFile.getAbsolutePath());
					if(!destFile.delete())
						throw new RuntimeException("Could not delete " + destFile.getAbsolutePath());
				}
				
				if(!destFile.exists() && !destFile.createNewFile()){
						throw new RuntimeException("Couldn't create " + destFile.getAbsolutePath());
				}
				copy(sourceFile, destFile);
//				System.out.println("<jar href=\"" + dep.getFile().getName() + "\"/>");
			}
			
			if(codeBase==null) codeBase = dir.toURL().toExternalForm();
			if(mainClass==null) mainClass = "some.class";
			
			
			if(templates.length==0){
				writeJnlpFile(readResourceAsString("template.jnlp.xml"), new File(dir, "default.jnlp"), jars);
			}else{
				for(String templateName: templates){
					System.out.println("TEMPLATE: " + templateName);
					File templateFile = new File(project.getBasedir(), templateName + ".jnlp");
					if(!templateFile.exists()){
						throw new RuntimeException("No such template file: " + templateFile.getAbsolutePath());
					}
					String template = readFileAsString(templateFile);
					File jnlpFile = new File(dir, templateName + ".jnlp");
					writeJnlpFile(template, jnlpFile, jars);
				}
			}
			
			// run script
			StringBuffer colonClasspath = new StringBuffer(thisJar.getName());
			StringBuffer semicolonClasspath = new StringBuffer(thisJar.getName());
			for (Artifact dep : dependencies) {
				File sourceFile = dep.getFile();
				File destFile = new File(dir, sourceFile.getName());
				
				colonClasspath.append(":");
				semicolonClasspath.append(";");
				
				colonClasspath.append(destFile.getName());
				semicolonClasspath.append(destFile.getName());
			}
			{// BASH SCRIPT
				File runScript = new File(dir, "run.sh");
				writeToFile("java -cp " + colonClasspath + " " + mainClass + " $@", runScript);
			}
			{// WINDOWS NT BATCH SCRIPT
				File runScript = new File(dir, "run.bat");
				writeToFile("java -cp " + semicolonClasspath + " " + mainClass + " %*", runScript);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new MojoFailureException(e.getMessage());
		}
		
//		
//		/*
//		 * load up all compiled classes to this point + their dependencies
//		 */
//
//		URLClassLoader cl = buildProjectClassloader();
//
//		/*
//		 * generate the specified interface wrapper classes
//		 */
//
//		File jaxwsDir = new File(project.getBasedir(), "src/main/java/");
//
//		if (!jaxwsDir.exists() && !jaxwsDir.mkdirs()) {
//			throw new MojoExecutionException("Could not create output directory: " + jaxwsDir.getAbsolutePath());
//		}
//
//		final List<File> filesToCompile = new ArrayList<File>();
//
//		for (String iface : interfaces) {
//
//			Class<?> clazz;
//			try {
//				clazz = cl.loadClass(iface);
//			}
//			catch (ClassNotFoundException ex) {
//				throw new MojoExecutionException("Class not found: " + iface);
//			}
//
//			String pkg = clazz.getPackage().getName().replaceAll("\\.", "/");
//			File targetPath = new File(jaxwsDir, pkg + "/jaxws");
//
//			if (getLog().isInfoEnabled()) {
//				getLog().info("Generating wrapper classes for SEI " + clazz.getName() + " in " + targetPath);
//			}
//
//		}
//
//		/*
//		 * compile the generated sources
//		 */
//
//		String classesDir = new File(project.getBuild().getDirectory(), "classes").getAbsolutePath();
//
//		String source = null;
//		String target = null;
//		boolean debug = false;
//		{
//
//			Xpp3Dom compilerConfig = null;
//
//			for (Object o : project.getBuildPlugins()) {
//				Plugin plugin = (Plugin)o;
//
//				if (plugin.getArtifactId().equals("maven-compiler-plugin")) {
//					compilerConfig = (Xpp3Dom) plugin.getConfiguration();
//					break;
//				}
//			}
//
//			if (compilerConfig != null) {
//
//				Xpp3Dom sourceNode = compilerConfig.getChild("source");
//				if (sourceNode != null) {
//					source = sourceNode.getValue();
//				}
//
//				Xpp3Dom targetNode = compilerConfig.getChild("target");
//				if (targetNode != null) {
//					target = targetNode.getValue();
//				}
//
//				Xpp3Dom debugNode = compilerConfig.getChild("debug");
//				if (debugNode != null) {
//					try {
//						debug = Boolean.parseBoolean(debugNode.getValue());
//					}
//					catch (Exception ex) {
//						if (getLog().isWarnEnabled()) {
//							getLog().warn("Could not determine the value of 'debug' while re-using the compiler plugin configuration.");
//						}
//					}
//				}
//			}
//		}
//
//		List<String> args = new ArrayList<String>();
//
//		if (source != null) {
//			args.add("-source");
//			args.add(source);
//		}
//
//		if (target != null) {
//			args.add("-target");
//			args.add(target);
//		}
//
//		if (debug) {
//			args.add("-g");
//		}
//
//		args.add("-cp");
//
//		String classpath;
//		{
//			StringBuilder cp = new StringBuilder();
//			int count = 0;
//			for (URL url : cl.getURLs()) {
//
//				cp.append(url.getFile());
//
//				if (count + 1 < cl.getURLs().length) {
//					cp.append(":");
//				}
//
//				count++;
//			}
//
//			classpath = cp.toString();
//		}
//		args.add(classpath);
//
//		args.add("-d"); 
//		args.add(classesDir);
//
//		for (File f : filesToCompile) {
//			args.add(f.getAbsolutePath());
//		}
//
//		if (getLog().isInfoEnabled()) {
//			getLog().info("Compiling " + filesToCompile.size() + " source files to " + classesDir);
//		}
//
//		if (getLog().isDebugEnabled()) {
//
//			StringBuilder cmd = new StringBuilder();
//
//			int i = 0;
//			for (String arg : args) {
//
//				cmd.append(arg);
//
//				if (i + 1 < args.size()) {
//					cmd.append(" ");
//				}
//
//				i++;
//			}
//
//			getLog().info("Executing: " + cmd);
//		}

	}

	private String print(List<String> args) {

		if (getLog().isDebugEnabled()) {

			StringBuilder cmd = new StringBuilder();

			int i = 0;
			for (String arg : args) {

				cmd.append(arg);

				if (i + 1 < args.size()) {
					cmd.append(" ");
				}

				i++;
			}

			return cmd.toString();
		}
		else {
			return "...";
		}
	}
	
	private Set<Artifact> dependencies() throws Exception {

		Set<Artifact> artifacts = project.createArtifacts(artifactFactory, null, null);
		ArtifactResolutionResult arr = resolver.resolveTransitively(artifacts, project.getArtifact(), localRepository, remoteRepositories, artifactMetadataSource, null);

		return arr.getArtifacts();
	}
	
	private URLClassLoader buildProjectClassloader() {
		try {

			Set<URL> classpathUrls = new HashSet<URL>();

			Set<Artifact> artifacts = project.createArtifacts(artifactFactory, null, null);
			ArtifactResolutionResult arr = resolver.resolveTransitively(artifacts, project.getArtifact(), localRepository, remoteRepositories, artifactMetadataSource, null);

			for (Artifact resolvedArtifact : (Set<Artifact>)arr.getArtifacts()) {
				classpathUrls.add(resolvedArtifact.getFile().toURL());
			}

			for (String e : classpathElements) {
				try {
					classpathUrls.add(new File(e).toURL());
				}
				catch (MalformedURLException ex) {
					throw new RuntimeException(ex);
				}
			}

			URLClassLoader mojoCl = (URLClassLoader)this.getClass().getClassLoader();

			Set<URL> duplicateUrls = new HashSet<URL>();
			for (URL alreadyLoadedUrl : mojoCl.getURLs()) {
				for (URL url : classpathUrls) {
					if (url.equals(alreadyLoadedUrl)) {
						duplicateUrls.add(url);
					}
				}
			}
			for (URL duplicateUrl : duplicateUrls) {
				classpathUrls.remove(duplicateUrl);
				getLog().debug("removing duplicate url from project-classpath: " + duplicateUrl);
			}

			URLClassLoader cl = new URLClassLoader(classpathUrls.toArray(new URL[0]), null);

			getLog().debug("dumping out project-classpath:");
			for (URL url : cl.getURLs()) {
				getLog().debug(url.toString());
			}

			return cl;
		}
		catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}


}
