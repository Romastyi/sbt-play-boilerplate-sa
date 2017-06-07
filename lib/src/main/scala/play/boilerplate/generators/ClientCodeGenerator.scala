package play.boilerplate.generators

import play.boilerplate.generators.injection.InjectionProvider
import play.boilerplate.parser.model._

class ClientCodeGenerator extends CodeGenerator {

  import GeneratorUtils._
  import treehugger.forest._
  import definitions._
  import treehuggerDSL._

  def generateImports(implicit ctx: GeneratorContext): Seq[Import] = {
    Seq(
      IMPORT(ctx.settings.modelPackageName, "_"),
      IMPORT(ctx.settings.jsonPackageName , "_"),
      IMPORT(ctx.settings.servicePackageName, ctx.settings.serviceClassName),
      IMPORT(ctx.settings.serviceClassName, "_"),
      IMPORT("play.api.http.HeaderNames", "_"),
      IMPORT("play.api.libs.ws", "_"),
      IMPORT("play.api.libs.json", "_"),
      IMPORT("play.api.libs.concurrent.Execution.Implicits", "_"),
      IMPORT("play.api.mvc", "_"),
      IMPORT("QueryStringBindable", "_"),
      IMPORT("PathBindable", "_"),
      IMPORT("scala.concurrent", "Future")
    ) ++
      ctx.settings.securityProvider.controllerImports ++
      ctx.settings.injectionProvider.imports ++
      Seq(ctx.settings.codeProvidedPackage).filterNot(_.isEmpty).map(IMPORT(_, "_"))
  }

  def dependencies(implicit cxt: GeneratorContext): Seq[InjectionProvider.Dependency] = {
    Seq(InjectionProvider.Dependency("WS", TYPE_REF("WSClient")))
  }

  override def generate(schema: Schema)(implicit ctx: GeneratorContext): Iterable[CodeFile] = {

    val methods = for {
      path <- schema.paths
      (_, operation) <- path.operations.toSeq.sortBy(_._1)
    } yield generateMethod(schema, path, operation)(ctx.addCurrentPath(operation.operationId).setInClient(true))

    val clientSources = if (methods.nonEmpty) {

      val clientImports = BLOCK {
        generateImports
      } inPackage ctx.settings.clientPackageName

      val classDef = CLASSDEF(ctx.settings.clientClassName)
        .withParents(TYPE_REF(ctx.settings.serviceClassName))
        .withParams(
          PARAM("baseUrl", StringClass).empty,
          PARAM("headers", TYPE_*(TYPE_TUPLE(StringClass, StringClass))).empty
        )
        .withSelf("self") :=
        BLOCK {
          filterNonEmptyTree(
            methods.flatMap(_.implicits).toIndexedSeq ++
            methods.map(_.tree).toIndexedSeq :+
            generateOrErrorMethod
          )
        }

      // --- DI
      val clientTree = ctx.settings.injectionProvider.classDefModifier(classDef, dependencies)

      SourceCodeFile(
        packageName = ctx.settings.clientPackageName,
        className = ctx.settings.clientClassName,
        header = treeToString(clientImports),
        impl = clientTree
      ) :: Nil

    } else {
      Nil
    }

    clientSources ++ generatePackageObject

  }

  def composeClientUrl(basePath: String, path: Path, operation: Operation): Tree = {

    val parts = path.pathParts.collect {
      case StaticPart(str) => LIT(str)
      case ParamPart(name) => REF("_render_path_param") APPLY (LIT(name), REF(name))
    }.toSeq

    val urlParts = (Seq(REF("baseUrl"), LIT(basePath.dropWhile(_ == '/'))) ++ parts).foldLeft(List.empty[Tree]) { case (acc, term) =>
      if (acc.isEmpty) acc :+ term else acc :+ LIT("/") :+ term
    }

    val urlParams: Seq[Tree] = (path.parameters ++ operation.parameters).toSeq collect {
      case param: QueryParameter => LIT(param.name) INFIX ("->", REF(param.name))
    }

    val baseUrl = INTERP(StringContext_s, urlParts: _ *)

    if (urlParams.isEmpty) {
      baseUrl
    } else {
      baseUrl INFIX("+", REF("_render_url_params") APPLY (urlParams: _*))
    }

  }

  case class Method(tree: Tree, implicits: Seq[Tree])

  def generateMethod(schema: Schema, path: Path, operation: Operation)(implicit ctx: GeneratorContext): Method = {

    val bodyParams = getBodyParameters(path, operation)
    val methodParams = getMethodParameters(path, operation)
    val securityParams = ctx.settings.securityProvider.getActionSecurity(operation.security.toIndexedSeq).securityParams
    val fullBodyParams = bodyParams.keys.map {
      name => name -> (REF("Json") DOT "toJson" APPLY REF(name))
    }.toMap

    val headerParams: Seq[Tree] = (path.parameters ++ operation.parameters).toSeq collect {
      case param: HeaderParameter =>
        val name = param.name
        val ref = param.ref match {
          case _: OptionDefinition => SOME(REF(name))
          case _ => REF(name)
        }
        LIT(name) INFIX ("->", ref)
    }

    val wsUrl = REF("WS") DOT "url" APPLY composeClientUrl(schema.basePath, path, operation)
    val wsUrlWithAccept = wsUrl DOT "withHeaders" APPLY (REF("ACCEPT") INFIX ("->", LIT("application/json")))
    val wsUrlWithHeaderParams = if (headerParams.isEmpty) {
      wsUrlWithAccept
    } else {
      wsUrlWithAccept DOT "withHeaders" APPLY SEQARG(REF("_render_header_params") APPLY (headerParams: _*))
    }
    val wsUrlWithHeaders = wsUrlWithHeaderParams DOT "withHeaders" APPLY SEQARG(REF("headers"))

    val RuntimeExceptionClass = definitions.getClass("java.lang.RuntimeException")

    val ERROR =
      REF("self") DOT "onError" APPLY (LIT(operation.operationId), REF("cause")) INFIX "map" APPLY BLOCK {
        LAMBDA(PARAM("errAnswer").tree) ==> THROW(NEW(RuntimeExceptionClass, REF("errAnswer"), REF("cause")))
      }

    val methodType = TYPE_REF(getOperationResponseTraitName(operation.operationId))

    val opType = operation.httpMethod.toString.toLowerCase
    val responses = generateResponses(operation)
    val methodTree = DEF(operation.operationId, FUTURE(methodType))
      .withFlags(Flags.OVERRIDE)
      .withParams(bodyParams.values.map(_.valDef) ++ methodParams.values.map(_.valDef) ++ securityParams.values) :=
      BLOCK {
        wsUrlWithHeaders DOT opType APPLY fullBodyParams.values DOT "map" APPLY {
          LAMBDA(PARAM("resp").tree) ==> BLOCK(responses.tree)
        } DOT "recoverWith" APPLY BLOCK {
          CASE(REF("cause") withType RootClass.newClass("Throwable")) ==> ERROR
        }
      }

    val tree = methodTree.withDoc(
      s"""${Option(operation.description).getOrElse("")}
         |
         """.stripMargin
    )

    val implicits = methodParams.values.flatMap(_.implicits).toSeq

    Method(tree, responses.implicits ++ implicits)

  }

  def generateResponses(operation: Operation)(implicit ctx: GeneratorContext): Method = {

    val responses = operation.responses.toSeq.sortBy {
      case (DefaultResponse, _) => 2
      case _ => 1
    }

    val cases = for ((code, response) <- responses) yield {
      val className = getResponseClassName(operation.operationId, code)
      val bodySupport = response.schema.map(body => getTypeSupport(body))
      val bodyParam = bodySupport.map { body =>
        REF("resp") DOT "json" DOT "as" APPLYTYPE body.tpe
      }
      val tree = code match {
        case DefaultResponse =>
          CASE(ID("status")) ==> (REF(className) APPLY (bodyParam.toSeq :+ REF("status")))
        case StatusResponse(status) =>
          CASE(LIT(status)) ==> bodyParam.map(bp => REF(className) APPLY bp).getOrElse(REF(className))
      }
      (tree, bodySupport.map(s => s.jsonReads ++ s.jsonWrites).getOrElse(Nil))
    }

    val UnexpectedResultCase = if (operation.responses.keySet(DefaultResponse)) {
      None
    } else {
      Some(
        CASE(ID("status")) ==> (REF(UnexpectedResult) APPLY (REF("resp") DOT "body", REF("status")))
      )
    }

    val tree = REF("resp") DOT "status" MATCH {
      cases.map(_._1) ++ UnexpectedResultCase
    }

    Method(tree, cases.flatMap(_._2))

  }

  def generateOrErrorMethod: Tree = {

    val operationId: ValDef = PARAM("operationId", StringClass.toType).tree
    val cause      : ValDef = PARAM("cause", RootClass.newClass("Throwable")).tree

    val methodTree = DEF("onError", FUTURE(StringClass.toType))
      .withFlags(Flags.OVERRIDE)
      .withParams(operationId, cause) :=
      BLOCK {
        REF("Future") DOT "successful" APPLY {
          INFIX_CHAIN("+",
            LIT("Operation '"),
            REF("operationId"),
            LIT("' error: "),
            REF("cause") DOT "getMessage"
          )
        }
      }

    methodTree.withDoc(
      "Error handler",
      DocTag.Param("operationId", "Operation where error was occurred"),
      DocTag.Param("cause"      , "An occurred error")
    )

  }

  final def generateRenderPathParam(packageName: String): Seq[Tree] = {
    val A = RootClass.newAliasType("A")
    val funcDef = DEF("_render_path_param", StringClass)
      .withFlags(PRIVATEWITHIN(packageName))
      .withTypeParams(TYPEVAR(A))
      .withParams(PARAM("key", StringClass).empty, PARAM("value", A).empty)
      .withParams(PARAM("pb", TYPE_REF("PathBindable") TYPE_OF A).withFlags(Flags.IMPLICIT).empty) :=
      BLOCK {
        REF("pb") DOT "unbind" APPLY (REF("key"), REF("value"))
      }
    funcDef :: Nil
  }

  final def generateRenderUrlParams(packageName: String): Seq[Tree] = {

    val imports = IMPORT("scala.language", "implicitConversions")

    val wrapper = RootClass.newClass("QueryValueWrapper")
    val wrapperDef = TRAITDEF(wrapper).withFlags(Flags.SEALED).empty

    val wrapperImpl = RootClass.newClass("QueryValueWrapperImpl")
    val wrapperImplDef = CASECLASSDEF(wrapperImpl)
      .withFlags(Flags.PRIVATE)
      .withParams(PARAM("unbind", StringClass TYPE_=> StringClass).empty)
      .withParents(wrapper)
      .empty

    val T = RootClass.newAliasType("T")
    val wrapperImplicit = DEF("toQueryValueWrapper", wrapper)
      .withTypeParams(TYPEVAR(T))
      .withParams(PARAM("value", T).empty)
      .withParams(PARAM("qb", TYPE_REF("QueryStringBindable") TYPE_OF T).withFlags(Flags.IMPLICIT).empty)
      .withFlags(Flags.IMPLICIT) :=
      BLOCK {
        wrapperImpl APPLY (REF("qb") DOT "unbind" APPLY (WILDCARD, REF("value")))
      }

    val funcDef = DEFINFER("_render_url_params")
      .withFlags(PRIVATEWITHIN(packageName))
      .withParams(PARAM("pairs", TYPE_*(TYPE_TUPLE(StringClass, wrapper))).tree) :=
      BLOCK(
        Seq(
          VAL("parts") := {
            REF("pairs") DOT "collect" APPLY BLOCK {
              CASE(TUPLE(ID("k"), REF("QueryValueWrapperImpl") UNAPPLY ID("unbind"))) ==>
                REF("unbind") APPLY REF("k")
            } DOT "filter" APPLY (WILDCARD DOT "nonEmpty")
          },
          IF (REF("parts") DOT "nonEmpty") THEN {
            REF("parts") DOT "mkString" APPLY(LIT("?"), LIT("&"), LIT(""))
          } ELSE {
            LIT("")
          }
        )
      )

    imports :: wrapperDef :: wrapperImplDef :: wrapperImplicit :: funcDef :: Nil

  }

  final def generateRenderHeaderParams(packageName: String): Seq[Tree] = {
    val fundDef = DEFINFER("_render_header_params")
      .withFlags(PRIVATEWITHIN(packageName))
      .withParams(PARAM("pairs", TYPE_*(TYPE_TUPLE(StringClass, OptionClass TYPE_OF AnyClass))).tree) :=
      BLOCK(
        Seq(
          REF("pairs")
            DOT "collect" APPLY BLOCK(CASE(TUPLE(ID("k"), REF("Some") UNAPPLY ID("v"))) ==>
            (REF("k") INFIX ("->", REF("v") DOT "toString")))
        )
      )
    fundDef :: Nil
  }

  final def generateUnexpectedResponseStatus: Seq[Tree] = {
    val classDef = CASECLASSDEF("UnexpectedResponseStatus")
      .withParams(PARAM("status", IntClass).tree, PARAM("body", StringClass).tree)
      .withParents(ThrowableClass, definitions.getClass("scala.util.control.NoStackTrace")) :=
      BLOCK {
        DEF("getMessage", StringClass).withFlags(Flags.OVERRIDE) := BLOCK {
          INFIX_CHAIN("+",
            LIT("unexpected response status: "),
            REF("status"),
            LIT(" "),
            REF("body")
          )
        }
      }
    classDef :: Nil
  }

  def generateHelpers(packageName: String)(implicit ctx: GeneratorContext): Seq[Tree] = {
    generateUnexpectedResponseStatus ++
    generateRenderPathParam(packageName) ++
    generateRenderUrlParams(packageName) ++
    generateRenderHeaderParams(packageName)
  }

  final def generatePackageObject(implicit ctx: GeneratorContext): Seq[SourceCodeFile] = {

    val splittedPackageName = ctx.settings.clientPackageName.split('.')
    val packageName = composeName(splittedPackageName.dropRight(1): _ *)
    val objectName = splittedPackageName.last
    val helpers = generateHelpers(objectName)

    if (helpers.nonEmpty) {
      val imports = BLOCK {
        IMPORT("play.api.mvc", "_")
      } inPackage packageName
      val objectTree = OBJECTDEF(objectName).withFlags(Flags.PACKAGE) := BLOCK(helpers)
      SourceCodeFile(
        packageName = ctx.settings.clientPackageName,
        className = objectName,
        header = treeToString(imports),
        impl = treeToString(objectTree)
      ) :: Nil
    } else {
      Nil
    }

  }

}