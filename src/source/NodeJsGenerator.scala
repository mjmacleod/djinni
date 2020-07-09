package djinni

import djinni.ast._
import djinni.generatorTools._
import djinni.meta._

import scala.collection.mutable

class NodeJsGenerator(spec: Spec, helperFiles: NodeJsHelperFilesDescriptor) extends Generator(spec) {

  protected val marshal = new NodeJsMarshal(spec)
  protected val cppMarshal = new CppMarshal(spec)

  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface): Unit = {

    val isNodeMode = true
    //Generate header
    generateInterface(origin, ident, doc, typeParams, i, isNodeMode)

    //Generate implementation file
    val baseClassName = marshal.typename(ident, i)

    if (i.ext.nodeJS) {

      val fileName = idNode.ty(ident.name) + ".cpp"
      createFile(spec.nodeOutFolder.get, fileName, { (w: writer.IndentWriter) =>

        w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
        w.wl("// This file generated by Djinni from " + origin)

        val hppFileName = "#include \"" + idNode.ty(ident.name) + "." + spec.cppHeaderExt + "\""
        w.wl
        w.wl(hppFileName)
        w.wl("using namespace std;")

        for (m <- i.methods) {

          val ret = cppMarshal.returnType(m.ret)
          val methodName = m.ident.name
          val methodNameImpl =  if (cppMarshal.isMaybeAsync(m.ret)) m.ident.name + "_aimpl__" else methodName
          val params = m.params.map(p => cppMarshal.paramType(p.ty.resolved) + " " + idNode.local(p.ident))
          var returnIsVoid = true;
          if (m.ret.isDefined && ret != "void")
          {
            returnIsVoid = false;
          }
          if (!m.static) {
            val constFlag = if (m.const) " const" else ""
            w.wl
            w.wl(s"$ret $baseClassName::$methodNameImpl${params.mkString("(", ", ", ")")}$constFlag").braced {
                w.wl("const auto& env = Env();")
                w.wl("Napi::HandleScope scope(env);")

                w.wl("//Wrap parameters")
                w.wl("std::vector<napi_value> args;")
                checkAndCastTypes(ident, i, m, w, returnIsVoid)

                val quotedMethod = s""""$methodName""""
                w.wl(s"Napi::Value calling_function_as_value = Value().Get($quotedMethod);")
                w.wl(s"if(!calling_function_as_value.IsUndefined() && !calling_function_as_value.IsNull())").braced {
                    w.wl(s"Napi::Function calling_function = calling_function_as_value.As<Napi::Function>();")
                    w.wl(s"auto result_$methodName = calling_function.Call(args);")
                    w.wl(s"if(result_$methodName.IsEmpty())").braced {
                        val error = s""""$baseClassName::$methodName call failed""""
                        w.wl(s"Napi::Error::New(env, $error).ThrowAsJavaScriptException();")
                        if (returnIsVoid)
                        {
                            w.wl("return;")
                        }
                        else
                        {
                            w.wl("return Napi::Value();")
                        }
                    }

                    if (m.ret.isDefined && ret != "void") {
                        w.wl(s"auto checkedResult_$methodName = result_$methodName.ToLocalChecked();")
                        marshal.toCppArgument(m.ret.get.resolved, s"fResult_$methodName", s"checkedResult_$methodName", w, returnIsVoid)
                        w.wl(s"return fResult_$methodName;")
                    }
                }
            }
            if (methodNameImpl != methodName)
            {
                val types = m.params.map(p => cppMarshal.toCppType(p.ty.resolved, None, Seq()))
                val names = m.params.map(p => idNode.local(p.ident))
                var arg_type_list: String = if (types.length > 0) types.mkString(", ", ", ", "") else s""
                var arg_get_list: String = s""
                var arg_name_list: String = if (names.length > 0) names.mkString(", ", ", ", "") else s""
                var tuple_type = s"$baseClassName*$arg_type_list"
                if(types.length > 0) {
                    for (i <- 0 to types.length - 1) {
                        var get_pos = i + 1
                        var get_string = s"std::get<${get_pos}>(*((std::tuple<$tuple_type>*)req->data))"
                        if (i == 0)
                        {
                            arg_get_list = get_string
                        }
                        else
                        {
                            arg_get_list = s"${arg_get_list}, ${get_string}"
                        }
                    }
                }
                w.wl
                w.wl(s"$ret $baseClassName::$methodName${params.mkString("(", ", ", ")")}$constFlag").braced {
                    //fixme: Leaking requests
                    w.wl(s"uv_work_t* request = new uv_work_t;")
                    w.wl(s"request->data = new std::tuple<$tuple_type>(this$arg_name_list);")
                    w.wl
                    w.wl(s"uv_queue_work(uv_default_loop(), request, [](uv_work_t*) -> void{}, [](uv_work_t* req, int status) -> void").braced {
                        w.wl(s"$baseClassName* pthis = std::get<0>(*((std::tuple<$tuple_type>*)req->data));")
                        w.wl(s"pthis->$methodNameImpl($arg_get_list);")
                        w.wl(s"delete (std::tuple<$tuple_type>*)req->data;")
                        w.wl(s"req->data = nullptr;")
                    }
                    w.wl(s");")
                }
            }
          }
        }
        //w.wl
        //createNanNewMethod(ident, i, None, w)
        //w.wl
        //createWrapMethod(ident, i, w)
        w.wl
        createInitializeMethod(ident, i, w)
      })
    }
  }

  protected def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface, nodeMode: Boolean)
  {
    val refs = new CppRefs(ident.name)
    i.methods.map(m => {
      m.params.map(p => refs.find(p.ty, true, nodeMode))
      m.ret.foreach((x) => refs.find(x, true, nodeMode))
    })

    if (refs.hpp("#include <memory>") &&
      refs.cpp("#include <memory>")) {
      refs.cpp.remove("#include <memory>")
    } else if (!nodeMode &&
      //For C++ interfaces we always have shared_ptr for c++ implementation member
      !refs.hpp("#include <memory>") &&
      !refs.cpp("#include <memory>")) {
      refs.hpp.add("#include <memory>")
    }

    val baseClassName = marshal.typename(ident, i)
    val cppClassName = cppMarshal.typename(ident, i)
    val className = baseClassName

    //Create .hpp file
    val cppInterfaceHpp = if (spec.nodeIncludeCpp.nonEmpty) "\"" + spec.nodeIncludeCpp + "/" + ident.name + "." + spec.cppHeaderExt + "\"" else "<" + ident.name + "." + spec.cppHeaderExt + ">"
    val cpp_shared_ptr = "std::shared_ptr<" + spec.cppNamespace + "::" + cppClassName + ">"

    val define = ("DJINNI_GENERATED_" + spec.nodeFileIdentStyle(ident.name) + "_" + spec.cppHeaderExt).toUpperCase

    if ((i.ext.nodeJS && nodeMode) || (i.ext.cpp && !nodeMode)) {

      var fileName = if (nodeMode) idNode.ty(ident.name) else idNode.ty(ident.name)
      fileName = s"$fileName.${spec.cppHeaderExt}"

      createFile(spec.nodeOutFolder.get, fileName, { (w: writer.IndentWriter) =>


        w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
        w.wl("// This file generated by Djinni from " + origin)
        w.wl
        w.wl(s"#ifndef $define")
        w.wl(s"#define $define")
        w.wl

        //Include hpp refs
        if (refs.hpp.nonEmpty) {
          w.wl
          refs.hpp.foreach(w.wl)
        }

        //Include cpp refs
        if (refs.cpp.nonEmpty) {
          w.wl
          refs.cpp.foreach(w.wl)
        }

        w.wl
        w.wl("#include <napi.h>")
        w.wl("#include <uv.h>")
        w.wl(s"#include $cppInterfaceHpp")
        w.wl
        w.wl("using namespace std;")
        if (spec.cppNamespace.nonEmpty) {
            w.wl(s"using namespace ${spec.cppNamespace};")
        }

        if (i.ext.nodeJS && refs.hppFwds.nonEmpty) {
          w.wl
          refs.hppFwds.foreach(w.wl)
        }

        var classInheritance = s"class $className: public Napi::ObjectWrap<$className>"
        if (nodeMode) {
          classInheritance = s"class $className: public Napi::ObjectWrap<$className>"
        }
        w.wl
        w.w(classInheritance).bracedSemi {

          w.wlOutdent("public:")
          w.wl
          w.wl("static Napi::FunctionReference constructor;")
          w.wl(s"static Napi::Object Init(Napi::Env env, Napi::Object exports);")
          //if (!nodeMode) {
            //Constructor
            w.wl(s"$className(const Napi::CallbackInfo& info) : Napi::ObjectWrap<$className>(info){};")
          //}

          if (nodeMode) {

            //For node implementation, use C++ types
            for (m <- i.methods) {
              val ret = cppMarshal.returnType(m.ret)
              val methodName = m.ident.name
              val params = m.params.map(p => cppMarshal.paramType(p.ty.resolved) + " " + idNode.local(p.ident))
              if (!m.static) {
                val constFlag = if (m.const) " const" else ""
                w.wl
                writeDoc(w, m.doc)
                w.wl(s"$ret $methodName${params.mkString("(", ", ", ")")}$constFlag;")
              }
            }

          }
          w.wl
          // Methods
          w.wlOutdent("private:")
          for (m <- i.methods) {
            val ret = cppMarshal.returnType(m.ret)
            val params = m.params.map(p => cppMarshal.paramType(p.ty.resolved) + " " + idNode.local(p.ident))
            val methodName = m.ident.name
            val methodNameImpl =  if (cppMarshal.isMaybeAsync(m.ret)) m.ident.name + "_aimpl__" else methodName
            writeDoc(w, m.doc)
            var nodeRet = "void";
            if (m.ret.isDefined && ret != "void") {
                nodeRet = "Napi::Value";
            }
            w.wl(s"$nodeRet $methodName(const Napi::CallbackInfo& info);")
            if (methodNameImpl != methodName) {
              if (!m.static) {
                val constFlag = if (m.const) " const" else ""
                w.wl(s"$ret $methodNameImpl${params.mkString("(", ", ", ")")}$constFlag;")
              }
            }
            w.wl
          }
        }
        w.wl(s"#endif //$define")
      })
    }
  }

  protected def checkAndCastTypes(ident: Ident, i: Interface, method: Interface.Method, wr: writer.IndentWriter, returnIsVoid: Boolean): Int = {

    var count = 0
    method.params.map(p => {
      val index = method.params.indexOf(p)
      if (i.ext.cpp) {
        marshal.toCppArgument(p.ty.resolved, s"arg_$index", s"info[$index]", wr, returnIsVoid)
      } else {
        marshal.fromCppArgument(p.ty.resolved, s"arg_$index", idNode.local(p.ident), wr)
        wr.wl(s"args.push_back(arg_$index);")
      }
      count = count + 1
    })
    count
  }

  protected def createInitializeMethod(ident: Ident, i: Interface, wr: writer.IndentWriter): Unit = {

    val baseClassName = marshal.typename(ident, i)
    val quotedClassName = "\"" + baseClassName + "\""
    
    wr.wl(s"Napi::FunctionReference $baseClassName::constructor;")
    wr.wl
    wr.w(s"Napi::Object $baseClassName::Init(Napi::Env env, Napi::Object exports)").braced
    {
        wr.wl
        if (i.ext.cpp)
        {
            wr.wl("// Hook all method callbacks")
            wr.wl(s"Napi::Function func = DefineClass(env, $quotedClassName, {")
            
            
                for (m <- i.methods)
                {
                    val methodName = m.ident.name
                    val quotedMethodName = "\"" + methodName + "\""
                    wr.wl(s"InstanceMethod($quotedMethodName, &$baseClassName::$methodName),")
                }
                //val quotedNull = "\"isNull\""
                //wr.wl(s"InstanceMethod<&$baseClassName::isNull>($quotedNull),")
            wr.wl("});")

            wr.wl("// Create a peristent reference to the class constructor. This will allow a function called on a class prototype and a function called on instance of a class to be distinguished from each other.")
            wr.wl("constructor = Napi::Persistent(func);")
            wr.wl("// Call the SuppressDestruct() method on the static data prevent the calling to this destructor to reset the reference when the environment is no longer available.")
            wr.wl("constructor.SuppressDestruct();")
            wr.wl(s"exports.Set($quotedClassName, func);")
        }
        else
        {
            wr.wl(s"Napi::Function func = DefineClass(env, $quotedClassName,{});")
            wr.wl("constructor = Napi::Persistent(func);")
            wr.wl("constructor.SuppressDestruct();")
            wr.wl(s"exports.Set($quotedClassName, func);")
        }
        wr.wl("return exports;")
    }
  }

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum) {
    
  }

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record) {
    
  }

  class CppRefs(name: String) {
    val hpp = mutable.TreeSet[String]()
    val hppFwds = mutable.TreeSet[String]()
    val cpp = mutable.TreeSet[String]()

    def find(ty: TypeRef, forwardDeclareOnly: Boolean, nodeMode: Boolean) {
      find(ty.resolved, forwardDeclareOnly, nodeMode)
    }

    def find(m: Meta, forwardDeclareOnly: Boolean, nodeMode: Boolean) = {
      for (r <- marshal.hppReferences(m, name, forwardDeclareOnly, nodeMode)) r match {
        case ImportRef(arg) => hpp.add("#include " + arg)
        case DeclRef(decl, Some(spec.cppNamespace)) => hppFwds.add(decl)
        case DeclRef(_, _) =>
      }
    }

    def find(tm: MExpr, forwardDeclareOnly: Boolean, nodeMode: Boolean) {
      tm.args.foreach((x) => find(x, forwardDeclareOnly, nodeMode))
      find(tm.base, forwardDeclareOnly, nodeMode)
    }
  }
}


