package scala.meta.internal.semanticdb.scalac

import java.net.{URI, URLEncoder}
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.collection.mutable
import scala.{meta => m}
import scala.meta.internal.io._
import scala.meta.io.AbsolutePath
import scala.reflect.internal.util.{Position => GPosition, SourceFile => GSourceFile}
import scala.reflect.io.VirtualFile
import scala.reflect.io.{PlainFile => GPlainFile}

trait InputOps { self: SemanticdbOps =>

  lazy val gSourceFileInputCache = mutable.Map[GSourceFile, m.Input]()
  implicit class XtensionGSourceFileInput(gsource: GSourceFile) {
    private def uriRelativeToSourceRoot(file: AbsolutePath): URI = {
      val fileUri = file.toURI(isDirectory = false)
      val result = config.sourceroot.toURI(isDirectory = true).relativize(fileUri)
      if (result == fileUri) {
        // java.net.URI.relativize returns `fileUri` unchanged when it is not contained within our sourceroot.
        // We could attempt to return a ".." URI, but java.net doesn't provide facilities for that. While nio's Path
        // does contain facilities for that, such relative paths cannot then be used to produce a percent-encoded,
        // relative URI. It doesn't seem worth fighting this battle at the moment, so:
        sys.error(s"'$file' is not located within sourceroot '${config.sourceroot}'.")
      }
      result
    }

    def isInSourceroot(sourceroot: AbsolutePath): Boolean = gsource.file match {
      case gfile: GPlainFile =>
        !config.sourceroot.toNIO.relativize(gfile.file.toPath).toString.contains("..")
      case _: VirtualFile =>
        true // Would anyone go to the trouble of building a VirtualFile that's outside of sourceroot?
      case _ =>
        false
    }

    def toUri: String = toInput match {
      case input: m.Input.File =>
        uriRelativeToSourceRoot(input.path).toString
      case input: m.Input.VirtualFile =>
        input.path
      case _ =>
        ""
    }
    def toText: String = toInput match {
      case _: m.Input.File =>
        "" // slim mode, don't embed contents
      case input: m.Input.VirtualFile if config.text.isOn =>
        input.value
      case _ =>
        ""
    }
    def toMD5: String = {
      if (config.md5.isOff) ""
      else {
        val md5 = MessageDigest.getInstance("MD5")
        val bytes = UTF_8.encode(CharBuffer.wrap(toInput.chars))
        md5.update(bytes)
        Hex.bytesToHex(md5.digest())
      }
    }
    def toInput: m.Input =
      gSourceFileInputCache.getOrElseUpdate(gsource, {
        gsource.file match {
          case gfile: GPlainFile =>
            if (config.text.isOn) {
              val path = m.AbsolutePath(gfile.file)
              val label = uriRelativeToSourceRoot(path).toString
              // NOTE: Can't use gsource.content because it's preprocessed by scalac.
              val contents = FileIO.slurp(path, UTF_8)
              m.Input.VirtualFile(label, contents)
            } else {
              m.Input.File(gfile.file)
            }
          case gfile: VirtualFile =>
            val uri = URLEncoder.encode(gfile.path, UTF_8.name)
            m.Input.VirtualFile(uri, gsource.content.mkString)
          case _ =>
            m.Input.None
        }
      })
  }

  implicit class XtensionGPositionMPosition(pos: GPosition) {
    def toMeta: m.Position = {
      // NOTE: Even with -Yrangepos enabled we cannot be guaranteed that all positions are
      // range positions. In the case we encounter a non-range position we assume start == end.
      val input = pos.source.toInput
      if (input == m.Input.None) m.Position.None
      else if (!pos.isDefined) m.Position.None
      else if (pos.isRange) m.Position.Range(input, pos.start, pos.end)
      else m.Position.Range(input, pos.point, pos.point)
    }
  }
}
