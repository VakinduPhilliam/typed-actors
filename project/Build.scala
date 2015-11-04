import com.typesafe.sbt.SbtGit.GitKeys
import com.typesafe.sbt.git.JGit
import sbt._
import sbt.Keys._
import de.knutwalker.sbt._
import de.knutwalker.sbt.KSbtKeys.{ akkaVersion ⇒ _, _ }
import com.typesafe.tools.mima.plugin.MimaKeys.binaryIssueFilters
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.{ Vcs, Version }

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

object Build extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = KSbtPlugin

  object autoImport {
    lazy val akkaActorVersion = settingKey[String]("Version of akka-actor.")
    lazy val latestVersionTag = settingKey[Option[String]]("The latest tag describing a version number.")
    lazy val latestVersion = settingKey[String]("The latest version or the current one, if there is no previous version.")
    lazy val isAkka24 = settingKey[Boolean]("Whether the build is compiled against Akka 2.4.x.")
    lazy val genModules = taskKey[Seq[(File, String)]]("Generate module files for guide.")
    lazy val makeReadme = taskKey[Option[File]]("Generate readme file from tutorial.")
    lazy val commitReadme = taskKey[Option[File]]("Commit the readme file.")
    lazy val buildReadmeContent = taskKey[Seq[(File, String)]]("Generate content for the readme file.")
    lazy val readmeFile = settingKey[File]("The readme file to build.")
    lazy val readmeCommitMessage = settingKey[String]("The message to commit the readme file with.")
    lazy val akkaPersistence = akkaPersistenceDependency
  }
  import autoImport._

  override lazy val projectSettings = mimaDefaultSettings ++ List(
         organization := "de.knutwalker",
            startYear := Some(2015),
           maintainer := "Paul Horn",
        githubProject := Github("knutwalker", "typed-actors"),
          description := "Compile time wrapper for more type safe actors",
         scalaVersion := "2.11.7",
     akkaActorVersion := "2.4.0",
             isAkka24 := akkaActorVersion.value.startsWith("2.4"),
  libraryDependencies += "com.typesafe.akka" %% "akka-actor" % akkaActorVersion.value % "provided",
          javaVersion := JavaVersion.Java17,
      autoAPIMappings := true,
     latestVersionTag := GitKeys.gitReader.value.withGit(g ⇒ findLatestVersion(g.asInstanceOf[JGit])),
        latestVersion := latestVersionTag.value.getOrElse(version.value),
     previousArtifact := latestVersionTag.value.map(v ⇒ organization.value %% name.value % v).filter(_ ⇒ publishArtifact.value),
  binaryIssueFilters ++= ignoredABIProblems,
         apiMappings ++= mapAkkaJar((externalDependencyClasspath in Compile).value, scalaBinaryVersion.value, akkaActorVersion.value),
           genModules := generateModules(state.value, sourceManaged.value, streams.value.cacheDirectory, thisProject.value.dependencies),
           makeReadme := mkReadme(state.value, buildReadmeContent.?.value.getOrElse(Nil), readmeFile.?.value, readmeFile.?.value),
         commitReadme := addAndCommitReadme(state.value, makeReadme.value, readmeCommitMessage.?.value, releaseVcs.value),
       releaseProcess := getReleaseSteps(isAkka24.value),
             pomExtra := pomExtra.value ++
               <properties>
                 <info.apiURL>http://{githubProject.value.org}.github.io/{githubProject.value.repo}/api/{version.value}/</info.apiURL>
               </properties>,
    unmanagedSourceDirectories in Compile ++= List(
      if (isAkka24.value) (sourceDirectory in Compile).value / s"scala-akka-2.4.x"
      else                (sourceDirectory in Compile).value / s"scala-akka-2.3.x",
      if (isAkka24.value) (sourceDirectory in (Test, test)).value / s"scala-akka-2.4.x"
      else                (sourceDirectory in (Test, test)).value / s"scala-akka-2.3.x"
    )
  )

  val akkaPersistenceDependency = (_: String) match {
    case x if x.startsWith("2.4") ⇒ "com.typesafe.akka" %% "akka-persistence" % x % "provided"
    case otherwise                ⇒ "com.typesafe.akka" %% "akka-persistence-experimental" % otherwise % "provided"
  }

  def findLatestVersion(git: JGit): Option[String] = {
    val tags = git.tags.collect {
      case tag if tag.getName startsWith "refs/tags/" ⇒
        tag.getName drop 10 replaceFirst ("^v", "")
    }
    val sortedTags = tags.flatMap(Version(_)).sorted.map(_.string)
    sortedTags.lastOption
  }

  lazy val ignoredABIProblems = {
    import com.typesafe.tools.mima.core._
    import com.typesafe.tools.mima.core.ProblemFilters._
    List(
      exclude[MissingMethodProblem]("de.knutwalker.akka.typed.TypedActor.untypedFromTyped")
    )
  }

  def getReleaseSteps(isAkka24: Boolean) = {
    val always = List(
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishSignedArtifacts,
      releaseToCentral
    )
    val versionSpecific =
      if (isAkka24) Nil else List(
        pushGithubPages,
        commitTheReadme,
        setNextVersion,
        commitNextVersion
      )
    val after = List(pushChanges)
    always ++ versionSpecific ++ after
  }

  def mapAkkaJar(cp: Seq[Attributed[File]], crossVersion: String, akkaVersion: String): Map[File, URL] =
    cp.collect {
      case file if file.data.toPath.endsWith(s"akka-actor_$crossVersion-$akkaVersion.jar") ⇒
        (file.data, url(s"http://doc.akka.io/api/akka/$akkaVersion/"))
    }.toMap

  def generateModules(state: State, dir: File, cacheDir: File, modules: Seq[ClasspathDep[ProjectRef]]): Seq[(File, String)] = {
    val files = new GenerateModulesTask(state, dir, cacheDir, modules.map(_.project)).apply()
    files.map(x ⇒ (x, x.getName))
  }

  val tutLine = raw"tut: (\d+)".r
  val titleLine = raw"title: (.+)".r
  val directLink = raw".*?\[&(?:l|r)aquo;.*?\]\([^/]+.html\).*".r
  val internalLink = raw".*\(([^/]+.html)\).*".r
  val slugify = (title: String) ⇒ title.replaceAll(raw"\s+", "-").toLowerCase(java.util.Locale.ENGLISH)
  val withoutExtension = (name: String) ⇒ name.substring(0, name.lastIndexOf('.'))

  case class TutFile(title: String, file: File, content: List[String], index: Int)

  def parseTutFiles(srcs: Seq[(File,String)]): Seq[TutFile] = {
    val sources = srcs.map(_._1)
    sources.flatMap(parseFrontMatter).sortBy(_.index)
  }

  def getTitles(tuts: Seq[TutFile]): Map[String, String] =
    ListMap(tuts.map(f ⇒ withoutExtension(f.file.getName) → f.title): _*)

  def parseFrontMatter(file: File): Option[TutFile] = {
    val lines = IO.readLines(file)
    val (front, content) = lines.dropWhile(_ == "---").span(_ != "---")
    for {
      index ← front.collectFirst {case tutLine(idx) ⇒ idx.toInt}
      title ← front.collectFirst {case titleLine(t) ⇒ t}
    } yield TutFile(title, file, content, index)
  }

  def parseTutContent(latest: String, titles: Map[String, String])(tut: TutFile): List[String] = {
    import tut._
    val actualContent = content.drop(1).withFilter {
      case directLink() ⇒ false
      case _            ⇒ true
    }.map(replaceLinks(latest, titles))
    "" :: "## " + title :: "" :: actualContent
  }

  @tailrec
  def replaceLinks(version: String, titles: Map[String, String])(line: String): String = line match {
    case line@internalLink(link) ⇒
      val newLink = titles.get(withoutExtension(link)).fold(link)(l ⇒ s"#${slugify(l)}")
      replaceLinks(version, titles)(line.replaceAllLiterally(link, newLink))
    case _                       ⇒ line.replaceAll(raw"\{\{ site\.data\.version\.version \}\}", version)
  }

  def mkReadme(state: State, srcs: Seq[(File,String)], tpl: Option[File], out: Option[File]): Option[File] = {
    tpl.filter(_.exists()).flatMap { template ⇒
      out.flatMap {outputFile ⇒
        val extracted = Project.extract(state)
        val latest = extracted.get(latestVersion)
        val tuts = parseTutFiles(srcs)
        val titles = getTitles(tuts)
        val lines = tuts.flatMap(parseTutContent(latest, titles))
        Some(lines).filter(_.nonEmpty).map { ls ⇒
          val targetLines = IO.readLines(template)
          val (head, middle) = targetLines.span(_ != "<!--- TUT:START -->")
          val (_, tail) = middle.span(_ != "<!--- TUT:END -->")
          IO.writeLines(outputFile, head)
          IO.writeLines(outputFile, middle.take(1), append = true)
          IO.writeLines(outputFile, makeLibraryDeps(extracted, latest), append = true)
          IO.writeLines(outputFile, Seq.fill(2)(""), append = true)
          IO.writeLines(outputFile, makeToc(titles), append = true)
          IO.writeLines(outputFile, ls, append = true)
          IO.writeLines(outputFile, tail, append = true)
          outputFile
        }
      }
    }
  }

  def makeLibraryDeps(extracted: Extracted, version: String): Seq[String] = {
    val modules = extracted.get(thisProject).dependencies.map(_.project)
    val dependencies = modules.flatMap { proj ⇒
      val org = extracted.get(organization in proj)
      val module = extracted.get(name in proj)
      List(s"""  "$org" %% "$module" % "$version"""", ",")
    }.init
    val deps = dependencies.grouped(2).map(_.mkString("")).toList
    "```scala" :: "libraryDependencies ++= List(" :: deps ::: ")" :: "```" :: Nil
  }

  def makeToc(titles: Map[String, String]): List[String] =
    List("## [Documentation][docs]", "") ++ titles.values.map { title ⇒
      s"- [$title](#${slugify(title)})"
    }

  def addAndCommitReadme(state: State, readme: Option[File], message: Option[String], maybeVcs: Option[Vcs]): Option[File] = for {
    vcs ← maybeVcs
    file ← readme
    msg ← message
    relative ← IO.relativize(vcs.baseDir, file)
    ff ← tryCommit(msg, vcs, file, relative, state.log)
  } yield ff

  def tryCommit(message: String, vcs: Vcs, file: File, relative: String, log: Logger): Option[File] = {
    vcs.add(relative) !! log
    val status = vcs.status.!!.trim
    if (status.nonEmpty) {
      vcs.commit(message) ! log
      Some(file)
    } else {
      None
    }
  }

  private class GenerateModulesTask(state: State, dir: File, cacheDir: File, modules: Seq[ProjectRef]) {
    val tempModulesFile = cacheDir / "gen-modules" / "modules.yml"
    val tempVersionFile = cacheDir / "gen-modules" / "version.yml"
    val modulesFile = dir / "modules.yml"
    val versionFile = dir / "version.yml"

    def apply(): Seq[File] = {
      mkFiles()
      List(
        cachedCopyOf(tempVersionFile, versionFile),
        cachedCopyOf(tempModulesFile, modulesFile)
      )
    }

    def mkFiles() = {
      val extracted = Project.extract(state)
      val latest = extracted.get(latestVersion)
      val lines = mkLines(extracted, latest)
      IO.writeLines(tempModulesFile, lines)
      IO.writeLines(tempVersionFile, s"version: $latest" :: Nil)
    }

    def cachedCopyOf(from: File, to: File): File = {
      val cacheFile = cacheDir / "gen-modules" / "cached-inputs" / from.getName
      val check = Tracked.inputChanged(cacheFile) {(hasChanged, input: HashFileInfo) ⇒
        if (hasChanged || !to.exists()) {
          IO.copyFile(from, to, preserveLastModified = true)
        }
      }
      check(FileInfo.hash(from))
      to
    }

    def mkLines(extracted: Extracted, latestVersion: String) =
      modules.flatMap { proj ⇒
        Seq(
          s"- organization: ${extracted.get(organization in proj)}",
          s"  name: ${extracted.get(name in proj)}",
          s"  version: $latestVersion"
        )
      }
  }

  implicit val versionOrdering = new Ordering[Version] {
    def compare(x: Version, y: Version): Int =
      x.major compare y.major match {
        case 0 ⇒ x.minor.getOrElse(0) compare y.minor.getOrElse(0) match {
          case 0 ⇒ x.bugfix.getOrElse(0) compare y.bugfix.getOrElse(0) match {
            case 0 ⇒ (x.qualifier, y.qualifier) match {
              case (None, None) ⇒ 0
              case (Some(_), Some(_)) ⇒ 0
              case (None, _) ⇒ 1
              case (_, None) ⇒ -1
            }
            case a ⇒ a
          }
          case a ⇒ a
        }
        case a ⇒ a
      }
  }

  private lazy val publishSignedArtifacts = ReleaseStep(
    action = Command.process("publishSigned", _),
    enableCrossBuild = true
  )

  private lazy val releaseToCentral = ReleaseStep(
    action = Command.process("sonatypeReleaseAll", _),
    enableCrossBuild = true
  )

  private lazy val pushGithubPages = ReleaseStep(
    action = Command.process("docs/ghpagesPushSite", _),
    enableCrossBuild = false
  )

  private lazy val commitTheReadme = ReleaseStep(
    action = Command.process("docs/commitReadme", _),
    enableCrossBuild = false
  )
}
