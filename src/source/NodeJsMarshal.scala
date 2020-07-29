package djinni

import djinni.ast._
import djinni.generatorTools._
import djinni.meta._
import djinni.writer.IndentWriter

import scala.collection.mutable.ListBuffer

class NodeJsMarshal(spec: Spec) extends CppMarshal(spec) {

  override def typename(tm: MExpr): String = toNodeType(tm, None, Seq())

  override def fqTypename(tm: MExpr): String = toNodeType(tm, Some(spec.cppNamespace), Seq())

  override def typename(name: String, ty: TypeDef): String = ty match {
    case e: Enum => idNode.enumType(name)
    case i: Interface => idNode.ty(name)
    case r: Record => idNode.ty(name)
  }

  override def paramType(tm: MExpr): String = toNodeParamType(tm)

  override def fqParamType(tm: MExpr): String = toNodeParamType(tm, Some(spec.cppNamespace))

  private def toNodeParamType(tm: MExpr, namespace: Option[String] = None, scopeSymbols: Seq[String] = Seq()): String = {
    toNodeType(tm, namespace, scopeSymbols)
  }

  private def toNodeType(tm: MExpr, namespace: Option[String], scopeSymbols: Seq[String]): String = {

    def base(m: Meta): String = m match {
      case p: MPrimitive => p.nodeJSName
      case MString => "String"
      case MDate => "Date"
      case MBinary => "Object"
      case MOptional => "MaybeLocal"
      case MList => "Array"
      case MSet => "Set"
      case MMap => "Map"
      case d: MDef =>
        d.defType match {
          case DInterface => withNamespace(idNode.ty(d.name), namespace, scopeSymbols)
          case _ => super.toCppType(tm, namespace, scopeSymbols)
        }
      case p: MParam => idNode.typeParam(p.name)
      case _ => super.toCppType(tm, namespace, scopeSymbols)
    }

    def expr(tm: MExpr): String = {
      spec.cppNnType match {
        case Some(nnType) =>
          // if we're using non-nullable pointers for interfaces, then special-case
          // both optional and non-optional interface types
          val args = if (tm.args.isEmpty) "" else tm.args.map(expr).mkString("<", ", ", ">")
          tm.base match {
            case d: MDef =>
              d.defType match {
                case DInterface => s"${nnType}<${withNamespace(idNode.ty(d.name), namespace, scopeSymbols)}>"
                case _ => base(tm.base) + args
              }
            case MOptional =>
              tm.args.head.base match {
                case d: MDef =>
                  d.defType match {
                    case DInterface => s"std::shared_ptr<${withNamespace(idCpp.ty(d.name), namespace, scopeSymbols)}>"
                    case _ => base(tm.base) + args
                  }
                case _ => base(tm.base) + args
              }
            case _ => base(tm.base) + args
          }
        case None =>
          if (isOptionalInterface(tm)) {
            // otherwise, interfaces are always plain old shared_ptr
            expr(tm.args.head)
          } else {
            base(tm.base)
          }
      }
    }

    expr(tm)
  }

  def toJSType(tm: MExpr): String = {
    def base(m: Meta): String = m match {
      case p: MPrimitive => p.jsName
      case MString => "string"
      case MList => s"Array<${toJSType(tm.args(0))}>"
      case MSet => s"Set<${toJSType(tm.args(0))}>"
      case MMap => s"Map<${toJSType(tm.args(0))}, ${toJSType(tm.args(1))}>"
      case MOptional => s"?${toJSType(tm.args(0))}"
      case _ => toNodeType(tm, None, Seq())
    }
    base(tm.base)
  }

  private def withNamespace(name: String, namespace: Option[String] = None, scopeSymbols: Seq[String] = Seq()): String = {

    val ns = namespace match {
      case Some(ns) => Some(ns)
      case None => if (scopeSymbols.contains(name)) Some(spec.cppNamespace) else None
    }
    withNs(ns, name)
  }

  override def returnType(ret: Option[TypeRef], scopeSymbols: Seq[String]): String = {
    ret.fold("void")(toNodeType(_, None, scopeSymbols))
  }

  override def returnType(ret: Option[TypeRef]): String = ret.fold("void")(toNodeType(_, None))

  private def toNodeType(ty: TypeRef, namespace: Option[String] = None, scopeSymbols: Seq[String] = Seq()): String =
    toNodeType(ty.resolved, namespace, scopeSymbols)

  override def fqReturnType(ret: Option[TypeRef]): String = {
    ret.fold("void")(toNodeType(_, Some(spec.cppNamespace)))
  }

  def hppReferences(m: Meta, exclude: String, forwardDeclareOnly: Boolean, nodeMode: Boolean, onlyNodeRef: Boolean = false): Seq[SymbolReference] = m match {
    case MOptional =>
      //If cppOptionalHeader is relative path, we have to concatenate with cpp include path
      var importRef = spec.cppOptionalHeader
      if(importRef.length > 0){
        importRef = importRef.slice(1, importRef.length - 1)
        importRef = if(importRef == '<') spec.cppOptionalHeader else s""""${spec.nodeIncludeCpp}/${importRef}""""
      }
      List(ImportRef(importRef))
    case d: MDef =>
      val nodeRecordImport = if(spec.nodeIncludeCpp.nonEmpty) s"${spec.nodeIncludeCpp}/${d.name}" else s"${d.name}"
      d.body match {
        case i: Interface =>
          val base = if (d.name != exclude) {

            var cppInterfaceImport = s""""${idNode.ty(d.name)}"""
            if (i.ext.cpp) {
              cppInterfaceImport = s"${cppInterfaceImport}Cpp"
            }

            cppInterfaceImport = s"""$cppInterfaceImport.${spec.cppHeaderExt}""""
            val nodeInterfaceImport = s""""${spec.nodeIncludeCpp}/${d.name}.${spec.cppHeaderExt}""""

            if (nodeMode && !onlyNodeRef) {
              List(ImportRef("<memory>"), ImportRef(cppInterfaceImport), ImportRef(nodeInterfaceImport))
            } else if(nodeMode && onlyNodeRef) {
              List(ImportRef(cppInterfaceImport))
            } else {
              List(ImportRef("<memory>"), ImportRef(cppInterfaceImport))
            }

          } else List(ImportRef("<memory>"))

          spec.cppNnHeader match {
            case Some(nnHdr) => ImportRef(nnHdr) :: base
            case _ => base
          }
        case r: Record =>
          if (d.name != exclude) {
            val localOnlyNodeRef = true
            var listOfReferences : Seq[SymbolReference] = List(ImportRef(include(nodeRecordImport, r.ext.cpp)))
            for (f <- r.fields) {
              val args = f.ty.resolved.args
              if(!args.isEmpty){
                args.foreach((arg)=> {
                  listOfReferences = listOfReferences ++ hppReferences(arg.base, exclude, forwardDeclareOnly, nodeMode, localOnlyNodeRef)
                })
              }
            }
            listOfReferences
          } else {
            List()
          }
        case e: Enum =>
          if (d.name != exclude) {
            List(ImportRef(include(nodeRecordImport)))
          } else {
            List()
          }
        case _ => super.hppReferences(m, exclude, forwardDeclareOnly)
      }
    case _ => super.hppReferences(m, exclude, forwardDeclareOnly)
  }

  override def include(ident: String, isExtendedRecord: Boolean = false): String = {
    val prefix = if (isExtendedRecord) spec.cppExtendedRecordIncludePrefix else spec.cppIncludePrefix
    q(prefix + spec.cppFileIdentStyle(ident) + "." + spec.cppHeaderExt)
  }

  override def toCpp(tm: MExpr, expr: String): String = throw new AssertionError("cpp to cpp conversion")

  override def fromCpp(tm: MExpr, expr: String): String = throw new AssertionError("cpp to cpp conversion")

  def toCppArgument(tm: MExpr, converted: String, converting: String, wr: IndentWriter, errorReturnIsVoid: Boolean): IndentWriter = {

    //Cast of List, Set and Map
    def toCppContainer(container: String, binary: Boolean = false, errorReturnIsVoid: Boolean = false): IndentWriter = {

      def toVector(cppTemplType: String, nodeTemplType: String): IndentWriter = {
        val containerName = s"${converted}_container"
        wr.wl(s"vector<$cppTemplType> $converted;")
        wr.wl(s"auto $containerName = Local<$container>::Cast($converting);")
        wr.wl(s"for(uint32_t ${converted}_id = 0; ${converted}_id < $containerName->Length(); ${converted}_id++)").braced {
          wr.wl(s"if($containerName->Get(${converted}_id)->Is$nodeTemplType())").braced {
            //Cast to c++ types
            if (!binary) {
              toCppArgument(tm.args(0), s"${converted}_elem", s"$containerName->Get(${converted}_id)", wr, errorReturnIsVoid)
            } else {
              //val context = "info.GetIsolate()->GetCurrentContext()"
              wr.wl(s"auto ${converted}_elem = Nan::To<uint32_t>($containerName->Get(${converted}_id)).FromJust();")
            }
            //Append to resulting container
            wr.wl(s"$converted.emplace_back(${converted}_elem);")
          }
        }
        wr.wl
      }

      if (!tm.args.isEmpty) {

        val cppTemplType = super.paramType(tm.args(0), true)
        val nodeTemplType = if(isInterface(tm.args(0)) || isRecord(tm.args(0))) "Object" else paramType(tm.args(0))

        if (container == "Map" && tm.args.length > 1) {

          val cppTemplValueType = super.paramType(tm.args(1), true)
          val nodeTemplValueType = if(isInterface(tm.args(1)) || isRecord(tm.args(0))) "Object" else paramType(tm.args(1))

          val containerName = s"${converted}_container"
          wr.wl(s"unordered_map<$cppTemplType, $cppTemplValueType> $converted;")

          //Get properties' names, loop over them and get their values
          val propertyNames = s"${converted}_prop_names"
          wr.wl(s"auto $propertyNames = $converting.ToObject().GetPropertyNames();")
          wr.wl(s"for(uint32_t ${converted}_id = 0; ${converted}_id < $propertyNames.Length(); ${converted}_id++)").braced {
            wr.wl(s"std::string ${converted}_key = $propertyNames.Get(${converted}_id).ToString();")
            wr.wl(s"std::string ${converted}_value = $propertyNames.Get(${converted}_key).ToString();")
            //Append to resulting container
            wr.wl(s"$converted.emplace(${converted}_key,${converted}_value);")
          }
          wr.wl
        } else {
          toVector(cppTemplType, nodeTemplType)
        }
      } else {
        if (binary) toVector("uint8_t", "Uint32") else wr.wl("//Type name not found !")
      }

    }

    def toSupportedCppNativeTypes(inputVarName:String, inputType: String): String = {
      inputType match {
        case "int8_t" | "int16_t" | "int32_t" => s"$inputVarName.ToNumber().Int32Value()"
        case "int64_t" => s"$inputVarName.ToNumber().Int64Value()"
        case "float" | "double" => s"$inputVarName.ToNumber().DoubleValue()"
        case "bool" => s"$inputVarName.ToBoolean().Value()"
        case _ => inputType
      }
    }

    val cppType = super.paramType(tm, needRef = true)
    var interfaceName = tm.base match {
      case d: MDef => idCpp.ty(d.name)
      case _ => cppType
    }
    val nodeType = paramType(tm)

    def base(m: Meta, errorReturnIsVoid: Boolean): IndentWriter = m match {
      case p: MPrimitive => 
        wr.wl(s"auto $converted = ${toSupportedCppNativeTypes(converting, p.cName)};")
      case MString =>
        wr.wl(s"std::string $converted = $converting.As<Napi::String>();")
      case MDate => {
        wr.wl(s"auto time_$converted = Nan::To<int32_t>($converting).FromJust();")
        wr.wl(s"auto $converted = chrono::system_clock::time_point(chrono::milliseconds(time_$converted));")
      }
      case MBinary => toCppContainer("Array", binary = true, errorReturnIsVoid)
      case MOptional => {

        val start = cppType.indexOf("<")
        val end = cppType.length - (cppType.reverse.indexOf(">") + 1)
        if(isInterface(tm.args(0))) {
          wr.wl(s"$cppType $converted = nullptr;")
        } else {
          wr.wl(s"auto $converted = ${spec.cppOptionalTemplate}<${cppType.substring(start + 1, end)}>();")
        }

        wr.wl(s"if(!$converting->IsNull() && !$converting->IsUndefined())").braced {
          toCppArgument(tm.args(0), s"opt_$converted", converting, wr, errorReturnIsVoid)
          if(isInterface(tm.args(0))) {
            wr.wl(s"$converted = opt_$converted;")
          } else {
            wr.wl(s"$converted.emplace(opt_$converted);")
          }
        }
        wr.wl
      }
      case MList => toCppContainer("Array", binary = false, errorReturnIsVoid)
      case MSet => toCppContainer("Set", binary = false, errorReturnIsVoid)
      case MMap => toCppContainer("Map", binary = false, errorReturnIsVoid)
      case d: MDef =>
        d.body match {
          case e: Enum =>
            val castToEnumType = s"(${spec.cppNamespace}::${idCpp.enumType(d.name)})"
            wr.wl(s"auto $converted = ${castToEnumType}Nan::To<int>($converting).FromJust();")
          case r: Record =>
            // Field definitions.
            var listOfRecordArgs = new ListBuffer[String]()
            var count = 1
            for (f <- r.fields) {
              wr.wl
              val fieldName = idCpp.field(f.ident)
              val quotedFieldName = s""""$fieldName""""
              wr.wl(s"auto field_${converted}_$count = $converting.ToObject().Get($quotedFieldName);")
              wr.wl(s"if (field_${converted}_$count.IsEmpty() || field_${converted}_$count.IsUndefined())").braced
              {
                val quotedErrorMessage = s""""Object is missing '$fieldName' field""""
                wr.wl(s"Napi::Error::New(env, $quotedErrorMessage).ThrowAsJavaScriptException();")
                if (errorReturnIsVoid)
                {
                    wr.wl("return;")
                }
                else
                {
                    wr.wl("return Napi::Value();")
                }
              }
              
              toCppArgument(f.ty.resolved, s"${converted}_$count", s"field_${converted}_$count", wr, errorReturnIsVoid)
              listOfRecordArgs += s"${converted}_$count"
              count = count + 1
            }
            wr.wl(s"${idCpp.ty(d.name)} $converted${listOfRecordArgs.toList.mkString("(", ", ", ")")};")
            wr.wl
          case i: Interface =>
            wr.wl(s"std::shared_ptr<$nodeType> $converted(std::shared_ptr<$nodeType>{}, $nodeType::Unwrap($converting.As<Napi::Object>()));");

            if(i.ext.cpp){
              wr.wl(s"if(!$converted)").braced{
                val error = s""""NodeJs Object to $nodeType failed""""
                wr.wl(s"Napi::Error::New(env, $error).ThrowAsJavaScriptException();")
              }
            }
            wr.wl
        }
      case e: MExtern => e.defType match {
        case DInterface => wr.wl(s"std::shared_ptr<${e.cpp.typename}>")
        case _ => wr.wl(e.cpp.typename)
      }
      case p: MParam => wr.wl(idNode.typeParam(p.name))
    }

    base(tm.base, errorReturnIsVoid)
  }

  def fromCppArgument(tm: MExpr, converted: String, converting: String, wr: IndentWriter): IndentWriter = {

    //Cast of List, Set and Map
    def fromCppContainer(container: String, binary: Boolean = false): IndentWriter = {

      def fromVector(): IndentWriter = {
        wr.wl(s"auto $converted = Napi::$container::New(env);")
        //Loop and cast elements of $converting
        wr.wl(s"for(size_t ${converted}_id = 0; ${converted}_id < $converting.size(); ${converted}_id++)").braced {
          //Cast
          if (!binary) {
            fromCppArgument(tm.args(0), s"${converted}_elem", s"$converting[${converted}_id]", wr)
          } else {
            wr.wl(s"auto ${converted}_elem = Napi::Value::From(env, $converting[${converted}_id]);")
          }
          wr.wl(s"$converted.Set((int)${converted}_id,${converted}_elem);")
        }
        wr.wl
      }

      if (!tm.args.isEmpty) {

        if (container == "Map" && tm.args.length > 1) {
          wr.wl(s"auto $converted = Napi::Object::New(env);")
          //Loop and cast elements of $converting
          wr.wl(s"for(auto const& ${converted}_elem : $converting)").braced {
            //Cast
            fromCppArgument(tm.args(0), s"${converted}_first", s"${converted}_elem.first", wr)
            fromCppArgument(tm.args(1), s"${converted}_second", s"${converted}_elem.second", wr)
            wr.wl(s"$converted.Set(${converted}_first, ${converted}_second);")
          }
          wr.wl

        } else {
          fromVector()
        }
      } else {
        if (binary) fromVector() else wr.wl("//Type name not found !")
      }

    }

    def simpleCheckedCast(nodeType: String, toCheck: Boolean = true): String = {
      s"auto $converted = Napi::$nodeType::New(env, $converting);"
    }
    def primitiveCast(nodeType: String, toCheck: Boolean = true): String = {
      s"auto $converted = Napi::Value::From(env, $converting);"
    }
    def base(m: Meta): IndentWriter = m match {
      case p: MPrimitive => wr.wl(primitiveCast(p.nodeJSName, false))
      case MString => wr.wl(simpleCheckedCast("String"))
      case MDate => {
        wr.wl(s"auto date_$converted = chrono::duration_cast<chrono::milliseconds>(${converting}.time_since_epoch()).count();")
        wr.wl(s"auto $converted = Nan::New<Date>(date_$converted).ToLocalChecked();")
      }
      case MBinary => fromCppContainer("Array", true)
      case MOptional => {
        if(!isInterface(tm.args(0))) {
          wr.wl(s"Local<Value> $converted;")
          wr.wl(s"if($converting)").braced {
            wr.wl(s"auto ${converted}_optional = ($converting).value();")
            fromCppArgument(tm.args(0), s"${converted}_tmp",  s"${converted}_optional", wr)
            wr.wl(s"$converted = ${converted}_tmp;")
          }
        } else {
          fromCppArgument(tm.args(0), converted, converting, wr)
        }
        wr.wl
      }
      case MList => fromCppContainer("Array")
      case MSet => fromCppContainer("Set")
      case MMap => fromCppContainer("Map")
      case d: MDef =>
        d.body match {
          case e: Enum => wr.wl(s"auto $converted = Napi::Value::From(env, (int)$converting);")
          case r: Record =>
            // Field definitions.
            wr.wl(s"auto $converted = Napi::Object::New(env);")
            var count = 1
            for (f <- r.fields) {
              val fieldName = idCpp.field(f.ident)
              fromCppArgument(f.ty.resolved, s"${converted}_$count", s"$converting.$fieldName", wr)
              val quotedFieldName = s""""$fieldName""""
              wr.wl(s"$converted.Set($quotedFieldName, ${converted}_$count);")
              count = count + 1
            }
            wr.wl
          case i: Interface =>
            val nodeType = paramType(tm)
            val cppType = super.paramType(tm, needRef = true)
            //Use wrap methods
            wr.wl(s"auto ${converted} = ${idNode.ty(d.name)}::wrap($converting);")
            wr.wl
        }
      case e: MExtern => e.defType match {
        case DInterface =>
          wr.wl(s"auto ${converted} = ${idNode.ty(e.name)}::wrap($converting);")
        case _ => wr.wl(e.cpp.typename)
      }
      case p: MParam => wr.wl(simpleCheckedCast("Object"))
    }

    base(tm.base)
  }
}

