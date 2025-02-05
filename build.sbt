/*
 * Copyright 2023 mixayloff-dimaaylov at github dot com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._

organization := "com.infocom"

name := "novatel-streaming"

ThisBuild / version := "0.3.0"

val assemblyName = "novatel-streaming-assembly"

ThisBuild / scalaVersion := "2.12.17"

scalariformAutoformat := false

scalastyleFailOnError := true

dependencyUpdatesFilter -= moduleFilter(organization = "org.scala-lang")

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8", // yes, this is 2 args
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Xfuture"
)

scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings")

// Ref: Disable warts for console
scalacOptions in (Compile, console) := (console / scalacOptions).value.filterNot(_.contains("wartremover"))

wartremoverErrors ++= Seq(
  Wart.StringPlusAny,
  Wart.EitherProjectionPartial,
  Wart.Enumeration,
  Wart.Equals,
  Wart.ExplicitImplicitTypes,
  Wart.FinalVal,
  Wart.IsInstanceOf,
  Wart.JavaConversions,
  Wart.LeakingSealed,
  Wart.TraversableOps,
  Wart.MutableDataStructures,
  Wart.Null,
  Wart.Option2Iterable,
  Wart.OptionPartial,
  Wart.Throw,
  Wart.TryPartial,
  Wart.While
)

val sparkVersion = "3.3.0"

val hbaseVersion = "1.2.0-cdh5.14.0"

resolvers ++= Seq(
  "cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos/"
)

resolvers += Resolver.typesafeRepo("releases")

val isALibrary = true //this is a library project

val assemblyDependencies = (scope: String) => Seq(
  "ch.hsr" % "geohash" % "1.3.0" % scope,
  "com.clickhouse" % "clickhouse-jdbc" % "0.3.2" % scope,

  "org.apache.spark" %% "spark-avro" % sparkVersion,
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion,
  "org.apache.spark" %% "spark-sql-kafka-0-10" % sparkVersion
)

/*if it's a library the scope is "compile" since we want the transitive dependencies on the library
  otherwise we set up the scope to "provided" because those dependencies will be assembled in the "assembly"*/
lazy val assemblyDependenciesScope: String = if (isALibrary) "compile" else "provided"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-yarn" % sparkVersion,
  "org.apache.spark" %% "spark-mllib" % sparkVersion,
  "org.apache.spark" %% "spark-streaming" % sparkVersion,
  "org.scalanlp" %% "breeze" % "2.1.0"
) ++ assemblyDependencies(assemblyDependenciesScope)

//Trick to make Intellij/IDEA happy
// We set all provided dependencies to none, so that they are included in the classpath of root module
//libraryDependencies := libraryDependencies.value.map{
//  module =>
//    if (module.configurations.equals(Some("provided"))) {
//      module.copy(configurations = None)
//    } else {
//      module
//    }
//}

//http://stackoverflow.com/questions/18838944/how-to-add-provided-dependencies-back-to-run-test-tasks-classpath/21803413#21803413
Compile / run := Defaults.runTask(Compile / fullClasspath, Compile / run / mainClass, Compile / run / runner)

//http://stackoverflow.com/questions/27824281/sparksql-missingrequirementerror-when-registering-table
fork := true

Test / parallelExecution := false

lazy val root = (project in file(".")).
  configs(IntegrationTest).
  settings(
    Defaults.itSettings,
    // Cache for speedup sbt on ephemeral Docker containers
    // Ref: https://github.com/sbt/sbt/issues/3270
    ivyPaths := { IvyPaths(baseDirectory.value, Some(target.value / ".ivy2")) },
  ).
  // enablePlugins(AutomateHeaderPlugin).
  enablePlugins(JavaAppPackaging).
  enablePlugins(AssemblyPlugin)

lazy val projectAssembly = (project in file("assembly")).
  settings(
    exportJars := true,
    assembly / test := {},
    assembly / assemblyOption := (assembly / assemblyOption).value,
    assembly / assemblyMergeStrategy := {
      // Literraly silver bullet:
      // Ref: http://java.msk.ru/literally-a-silver-bullet-for-sbt-merge-strategies-in-projects-using-spark-structured-streaming-and-kafka/
      case "META-INF/services/org.apache.spark.sql.sources.DataSourceRegister" => MergeStrategy.concat
      case PathList("META-INF", xs @ _*) =>
        xs map {_.toLowerCase} match {
          case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) =>
            MergeStrategy.discard
                    case "services" :: _ =>  MergeStrategy.filterDistinctLines
          case ps @ (x :: xs) if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
            MergeStrategy.discard
          case _ => MergeStrategy.first
        }
      case _ => MergeStrategy.first
    },
    assembly / assemblyJarName := s"$assemblyName-${version.value}.jar",
    libraryDependencies ++= assemblyDependencies("compile")
  ) dependsOn root settings (
  projectDependencies := {
    Seq(
      (root / projectID).value.excludeAll(ExclusionRule(organization = "org.apache.spark"),
        if (!isALibrary) ExclusionRule(organization = "org.apache.hadoop") else ExclusionRule())
    )
  })

Universal / mappings := {
  val universalMappings = (Universal / mappings).value
  val filtered = universalMappings filter {
    case (f, n) =>
      !n.endsWith(s"${organization.value}.${name.value}-${version.value}.jar")
  }

  val fatJar: File = new File(s"${System.getProperty("user.dir")}/target/scala-2.12/${name.value}_2.12-${version.value}.jar")
  filtered :+ (fatJar -> ("lib/" + fatJar.getName))
}

scriptClasspath ++= Seq(s"$assemblyName-${version.value}.jar")
