# Jenkins Test Selection plugin

Jenkins plugin to select tests with information retrieval based on code changes.

To build the plugin, run `mvn clean install package`

The plugin file will be located at:<br/> `target/test-selection.hpi`<br/>
Upload the plugin to the Jenkins server and restart. When creating a new project in Jenkins, or configuring an existing one, select the option <em>Use Test Selection</em> under <em>Build Environment</em>

A Git repository must be attached to the project. When configuring your project within Jenkins, under <em>Source Code Management</em>, select <em>Git</em> and enter your Git repository's URL under <em>Repository URL</em> and enter credentials if needed. When building your project now, either automatically via GitHub, or manually by clicking on <em>Build Now</em>, the test selection plugin will be executed, run selected tests, and produce a report.

The report will be located under <em>Build Artifacts</em> for the build, and is titled `results.html`

`example_report.html` is an example of the report page after executing a build with the plugin.

## Dependencies

This project was developed and tested with the following dependencies:
- Java 8 (jdk 1.8.0_181)
- Jenkins 2.7.3
- jenkins-ci.plugins 2.33
- Last Changes 2.6.8
- JavaParser 3.6.26
- Maven Invoker 3.0.1
- Diffparser 1.4
- Apache Lucene 5.3.1
- Maven Surefire 2.20
