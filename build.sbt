import scala.util.control.Exception.catching
import _root_.bintray.InternalBintrayKeys._
import _root_.bintray.{BintrayRepo, Bintray}
import NativePackagerHelper._

lazy val sbtOfflineInstall =
  sys.props.getOrElse("sbt.build.offline", sys.env.getOrElse("sbt.build.offline", "false")) match {
    case "true" => true
    case "1"    => true
    case _      => false
  }
lazy val sbtVersionToRelease = sys.props.getOrElse("sbt.build.version", sys.env.getOrElse("sbt.build.version", {
        sys.error("-Dsbt.build.version must be set")
      }))
lazy val isExperimental = (sbtVersionToRelease contains "RC") || (sbtVersionToRelease contains "M")
val sbtLaunchJarUrl = SettingKey[String]("sbt-launch-jar-url")
val sbtLaunchJarLocation = SettingKey[File]("sbt-launch-jar-location")
val sbtLaunchJar = TaskKey[File]("sbt-launch-jar", "Resolves SBT launch jar")
val moduleID = (organization) apply { (o) => ModuleID(o, "sbt", sbtVersionToRelease) }

val bintrayLinuxPattern = "[module]/[revision]/[module]-[revision].[ext]"
val bintrayGenericPattern = "[module]/[revision]/[module]/[revision]/[module]-[revision].[ext]"
val bintrayDebianUrl = "https://api.bintray.com/content/sbt/debian/"
val bintrayDebianExperimentalUrl = "https://api.bintray.com/content/sbt/debian-experimental/"
val bintrayRpmUrl = "https://api.bintray.com/content/sbt/rpm/"
val bintrayRpmExperimentalUrl = "https://api.bintray.com/content/sbt/rpm-experimental/"
val bintrayGenericPackagesUrl = "https://api.bintray.com/content/sbt/native-packages/"
val bintrayReleaseAllStaged = TaskKey[Unit]("bintray-release-all-staged", "Release all staged artifacts on bintray.")
val windowsBuildId = settingKey[Int]("build id for Windows installer")

// This build creates a SBT plugin with handy features *and* bundles the SBT script for distribution.
val root = (project in file(".")).
  enablePlugins(UniversalPlugin, LinuxPlugin, DebianPlugin, RpmPlugin, WindowsPlugin,
    UniversalDeployPlugin, DebianDeployPlugin, RpmDeployPlugin, WindowsDeployPlugin).
  settings(
    organization := "org.scala-sbt",
    name := "sbt-launcher-packaging",
    packageName := "sbt",
    version := "0.1.0",
    crossTarget := target.value,
    clean := {
      val _ = (clean in dist).value
      clean.value
    },
    publishToSettings,
    sbtLaunchJarUrl := downloadUrlForVersion(sbtVersionToRelease),
    sbtLaunchJarLocation := { target.value / "sbt-launch.jar" },
    sbtLaunchJar := {
      val uri = sbtLaunchJarUrl.value
      val file = sbtLaunchJarLocation.value
      import dispatch.classic._
      if(!file.exists) {
         // oddly, some places require us to create the file before writing...
         IO.touch(file)
         val writer = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))
         try Http(url(uri) >>> writer)
         finally writer.close()
      }
      // TODO - GPG Trust validation.
      file
    },
    // GENERAL LINUX PACKAGING STUFFS
    maintainer := "Eugene Yokota <eugene.yokota@lightbend.com>",
    packageSummary := "sbt, the interactive build tool",
    packageDescription := """This script provides a native way to run sbt,
  a build tool for Scala and more.""",
    // Here we remove the jar file and launch lib from the symlinks:
    linuxPackageSymlinks := {
      val links = linuxPackageSymlinks.value
      for {
        link <- links
        if !(link.destination endsWith "sbt-launch-lib.bash")
        if !(link.destination endsWith "sbt-launch.jar")
      } yield link
    },
    // DEBIAN SPECIFIC
    version in Debian := sbtVersionToRelease,
    debianPackageDependencies in Debian ++= Seq("openjdk-8-jdk", "bash (>= 3.2)"),
    debianPackageRecommends in Debian += "git",
    linuxPackageMappings in Debian += {
      val bd = sourceDirectory.value
      (packageMapping(
        (bd / "debian" / "changelog") -> "/usr/share/doc/sbt/changelog.gz"
      ) withUser "root" withGroup "root" withPerms "0644" gzipped) asDocs()
    },
    debianChangelog in Debian := { Some(sourceDirectory.value / "debian" / "changelog") },
    // RPM SPECIFIC
    version in Rpm := {
      val stable = (sbtVersionToRelease split "[^\\d]" filterNot (_.isEmpty) mkString ".")
      if (isExperimental) ((sbtVersionToRelease split "[^\\d]" filterNot (_.isEmpty)).toList match {
        case List(a, b, c, d) => List(0, 99, c, d).mkString(".")
      })
      else stable
    },
    rpmRelease := "1",
    rpmVendor := "lightbend",
    rpmUrl := Some("http://github.com/sbt/sbt-launcher-package"),
    rpmLicense := Some("BSD"),
    rpmRequirements := Seq("java-1.8.0-devel"),
    rpmProvides := Seq("sbt"),

    // WINDOWS SPECIFIC
    windowsBuildId := 1,
    version in Windows := {
      val bid = windowsBuildId.value
      val sv = sbtVersionToRelease
      (sv split "[^\\d]" filterNot (_.isEmpty)) match {
        case Array(major,minor,bugfix, _*) => Seq(major, minor, bugfix, bid.toString) mkString "."
        case Array(major,minor) => Seq(major, minor, "0", bid.toString) mkString "."
        case Array(major) => Seq(major, "0", "0", bid.toString) mkString "."
      }
    },
    maintainer in Windows := "Lightbend, Inc.",
    packageSummary in Windows := "sbt " + (version in Windows).value,
    packageDescription in Windows := "The interactive build tool.",
    wixProductId := "ce07be71-510d-414a-92d4-dff47631848a",
    wixProductUpgradeId := Hash.toHex(Hash((version in Windows).value)).take(32),
    javacOptions := Seq("-source", "1.5", "-target", "1.5"),

    // Universal ZIP download install.
    packageName in Universal := packageName.value, // needs to be set explicitly due to a bug in native-packager
    version in Universal := sbtVersionToRelease,
    mappings in Universal ++= {
      val launchJar = sbtLaunchJar.value
      val rtExportJar = (packageBin in Compile in java9rtexport).value
      Seq(launchJar -> "bin/sbt-launch.jar", rtExportJar -> "bin/java9-rt-export.jar")
    },
    mappings in Universal ++= (Def.taskDyn {
      if (sbtOfflineInstall)
        Def.task {
          val _ = (exportRepo in dist).value
          directory((target in dist).value / "lib")
        }
      else Def.task { Seq[(File, String)]() }
    }).value,
    stage in Universal := {
      val old = (stage in Universal).value
      val sd = (stagingDirectory in Universal).value
      val x = IO.read(sd / "bin" / "sbt-launch-lib.bash")
      IO.write(sd / "bin" / "sbt-launch-lib.bash", x.replaceAllLiterally("declare init_sbt_version=", s"declare init_sbt_version=$sbtVersionToRelease"))
      val y = IO.read(sd / "bin" / "sbt.bat")
      IO.write(sd / "bin" / "sbt.bat", y.replaceAllLiterally("set INIT_SBT_VERSION=", s"set INIT_SBT_VERSION=$sbtVersionToRelease"))
      old
    },

    // Misccelaneous publishing stuff...
    projectID in Debian := moduleID.value,
    projectID in Windows := {
      val m = moduleID.value
      m.copy(revision = (version in Windows).value)
    },
    projectID in Rpm := moduleID.value,
    projectID in Universal := moduleID.value
  )

lazy val java9rtexport = (project in file("java9-rt-export"))
  .settings(
    name := "java9-rt-export",
    autoScalaLibrary := false,
    crossPaths := false,
    description := "Exports the contents of the Java 9. JEP-220 runtime image to a JAR for compatibility with older tools.",
    homepage := Some(url("http://github.com/retronym/" + name.value)),
    startYear := Some(2017),
    licenses += ("Scala license", url(homepage.value.get.toString + "/blob/master/LICENSE")),
    mainClass in Compile := Some("io.github.retronym.java9rtexport.Export")
  )

def downloadUrlForVersion(v: String) = (v split "[^\\d]" flatMap (i => catching(classOf[Exception]) opt (i.toInt))) match {
  case Array(0, 11, 3, _*)           => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.11.3-2/sbt-launch.jar"
  case Array(0, 11, x, _*) if x >= 3 => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"
  case Array(0, y, _*) if y >= 12    => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"
  case Array(1, _, _*)               => "http://repo.scala-sbt.org/scalasbt/maven-releases/org/scala-sbt/sbt-launch/"+v+"/sbt-launch.jar"
  case _                             => "http://repo.typesafe.com/typesafe/ivy-releases/org.scala-tools.sbt/sbt-launch/"+v+"/sbt-launch.jar"
}

def makePublishTo(id: String, url: String, pattern: String): Setting[_] = {
  publishTo := {
    val resolver = Resolver.url(id, new URL(url))(Patterns(pattern))
    Some(resolver)
  }
}

def makePublishToForConfig(config: Configuration) = {
  val v = sbtVersionToRelease
  val (id, url, pattern) =
    config.name match {
      case Debian.name if isExperimental => ("debian-experimental", bintrayDebianExperimentalUrl, bintrayLinuxPattern)
      case Debian.name => ("debian", bintrayDebianUrl, bintrayLinuxPattern)
      case Rpm.name if isExperimental => ("rpm-experimental", bintrayRpmExperimentalUrl, bintrayLinuxPattern)
      case Rpm.name    => ("rpm", bintrayRpmUrl, bintrayLinuxPattern)
      case _           => ("native-packages", bintrayGenericPackagesUrl, bintrayGenericPattern)
    }
  // Add the publish to and ensure global resolvers has the resolver we just configured.
  inConfig(config)(Seq(
    bintrayOrganization := Some("sbt"),
    bintrayRepository := id,
    bintrayRepo := Bintray.cachedRepo(bintrayEnsureCredentials.value,
      bintrayOrganization.value,
      bintrayRepository.value),
    bintrayPackage := "sbt",
    makePublishTo(id, url, pattern),
    bintrayReleaseAllStaged := bintrayRelease(bintrayRepo.value, bintrayPackage.value, version.value, sLog.value)
    // Uncomment to release right after publishing
    // publish <<= (publish, bintrayRepo, bintrayPackage, version, sLog) apply { (publish, bintrayRepo, bintrayPackage, version, sLog) =>
    //   for {
    //     pub <- publish
    //     repo <- bintrayRepo
    //   } yield bintrayRelease(repo, bintrayPackage, version, sLog)
    // }
  )) ++ Seq(
     resolvers ++= ((publishTo in config) apply (_.toSeq)).value
  )
}

def publishToSettings =
  Seq[Configuration](Debian, Universal, Windows, Rpm) flatMap makePublishToForConfig

def bintrayRelease(repo: BintrayRepo, pkg: String, version: String, log: Logger): Unit =
  repo.release(pkg, version, log)


lazy val scala210 = "2.10.6"
lazy val scala212 = "2.12.1"
lazy val scala210Jline = "org.scala-lang" % "jline" % scala210
lazy val jansi = "org.fusesource.jansi" % "jansi" % "1.4"
lazy val scala212Jline = "jline" % "jline" % "2.14.1"
lazy val scala212Xml = "org.scala-lang.modules" % "scala-xml_2.12" % "1.0.6"
lazy val scala212Compiler = "org.scala-lang" % "scala-compiler" % scala212
lazy val sbtActual = "org.scala-sbt" % "sbt" % sbtVersionToRelease

def downloadUrl(uri: URI, out: File): Unit =
  {
    import dispatch.classic._
    if(!out.exists) {
       IO.touch(out)
       val writer = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))
       try Http(url(uri.toString) >>> writer)
       finally writer.close()
    }
  }

lazy val dist = (project in file("dist"))
  .enablePlugins(ExportRepoPlugin)
  .settings(
    name := "dist",
    scalaVersion := scala210,
    libraryDependencies ++= Seq(sbtActual, scala210Jline, jansi, scala212Compiler, scala212Jline, scala212Xml),
    exportRepo := {
      val old = exportRepo.value
      sbtVersionToRelease match {
        case v if v.startsWith("0.13.") =>
          val outbase = exportRepoDirectory.value / "org.scala-sbt" / "compiler-interface" / v
          val uribase = s"https://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/compiler-interface/$v/"
          downloadUrl(uri(uribase + "ivys/ivy.xml"), outbase / "ivys" / "ivy.xml")
          downloadUrl(uri(uribase + "jars/compiler-interface.jar"), outbase / "jars" / "compiler-interface.jar")
          downloadUrl(uri(uribase + "srcs/compiler-interface-sources.jar"), outbase / "srcs" / "compiler-interface-sources.jar")
        case _ =>
      }
      old
    },
    exportRepoDirectory := target.value / "lib" / "local-preloaded",
    conflictWarning := ConflictWarning.disable,
    publish := (),
    publishLocal := (),
    resolvers += Resolver.typesafeIvyRepo("releases")
  )
