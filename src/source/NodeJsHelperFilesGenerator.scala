package djinni

import java.io.File

import djinni.ast._
import djinni.generatorTools._

class NodeJsHelperFilesGenerator(spec: Spec, helperFileDescriptor: NodeJsHelperFilesDescriptor) extends Generator(spec) {

  override def generate(idl: Seq[TypeDecl]): Unit = {}

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum): Unit = ???

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record): Unit = ???

  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface): Unit = ???
}


