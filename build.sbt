lazy val common = Seq(
  organization := "com.github.romastyi",
  version := "0.0.1-SNAPSHOT",
  crossScalaVersions := Seq("2.10.4"),
  scalacOptions ++= Seq("-target:jvm-1.7", "-feature", "-deprecation", "-language:_"),
  resolvers += Resolver.sonatypeRepo("releases")
) ++ sonatypePublish

lazy val lib = project
  .in(file("lib"))
  .settings(common: _ *)
  .settings(
    name := """sbt-play-boilerplate-lib""",
    libraryDependencies ++= Seq(
      "com.eed3si9n" %% "treehugger" % "0.4.1",
      "io.swagger" % "swagger-parser" % "1.0.27",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    )
  )

lazy val plugin = project
  .in(file("plugin"))
  .settings(common: _ *)
  .settings(
    name := """sbt-play-boilerplate""",
    sbtPlugin := true
  )
  .dependsOn(lib)

lazy val root = project
  .in(file("."))
  .settings(
    publish := {}
  )
  .aggregate(lib, plugin)

publishArtifact := false

lazy val sonatypePublish = sonatypeSettings ++ Seq(
  publishMavenStyle := true,
  pomIncludeRepository := { _ =>
    false
  },
  credentials += Credentials(Path.userHome / ".sbt" / "nexus.credentials"),
  pomExtra := {
    <url>https://github.com/Romastyi/sbt-play-boilerplate</url>
      <licenses>
        <license>
          <name>Apache 2</name>
          <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
      </licenses>
      <developers>
        <developer>
          <id>andreaTP</id>
          <name>Andrea Peruffo</name>
          <url>https://github.com/andreaTP/</url>
        </developer>
        <developer>
          <id>fralken</id>
          <name>Francesco Montecuccoli Degli Erri</name>
          <url>https://github.com/fralken/</url>
        </developer>
        <developer>
          <id>mfirry</id>
          <name>Marco Firrincieli</name>
          <url>https://github.com/mfirry/</url>
        </developer>
        <developer>
          <id>romastyi</id>
          <name>Romastyi</name>
          <url>https://github.com/Romastyi/</url>
        </developer>
      </developers>
  }
)
