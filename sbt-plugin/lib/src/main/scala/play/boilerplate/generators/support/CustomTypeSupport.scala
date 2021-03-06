package play.boilerplate.generators.support

import play.boilerplate.generators.GeneratorContext
import play.boilerplate.parser.model._

trait CustomTypeSupport {

  import CustomTypeSupport._

  def getComplexTypeSupport: GeneratorContext => ComplexTypeSupport

  def getSimpleTypeSupport: GeneratorContext => SimpleTypeSupport

  def ++(other: CustomTypeSupport): CustomTypeSupport = {
    val sts0: GeneratorContext => SimpleTypeSupport = { ctx =>
      this.getSimpleTypeSupport(ctx) orElse other.getSimpleTypeSupport(ctx)
    }
    val cts0: GeneratorContext => ComplexTypeSupport = { ctx =>
      this.getComplexTypeSupport(ctx) orElse other.getComplexTypeSupport(ctx)
    }
    new CustomTypeSupport {
      override def getSimpleTypeSupport: GeneratorContext => SimpleTypeSupport = sts0
      override def getComplexTypeSupport: GeneratorContext => ComplexTypeSupport = cts0
    }
  }

}

object CustomTypeSupport {

  import treehugger.forest._
  import definitions._
  import treehuggerDSL._

  type ComplexTypeSupport = PartialFunction[(ComplexDefinition, DefinitionContext), TypeSupport]
  type SimpleTypeSupport  = PartialFunction[SimpleDefinition, TypeSupport]

  def empty: CustomTypeSupport = {
    new CustomTypeSupport {
      override val getComplexTypeSupport: GeneratorContext => ComplexTypeSupport = _ => Map.empty
      override val getSimpleTypeSupport: GeneratorContext => SimpleTypeSupport = _ => Map.empty
    }
  }

  def complex(f: GeneratorContext => ComplexTypeSupport): CustomTypeSupport = {
    new CustomTypeSupport {
      override val getComplexTypeSupport: GeneratorContext => ComplexTypeSupport = f
      override val getSimpleTypeSupport: GeneratorContext => SimpleTypeSupport = _ => Map.empty
    }
  }

  def simple(f: GeneratorContext => SimpleTypeSupport): CustomTypeSupport = {
    new CustomTypeSupport {
      override val getComplexTypeSupport: GeneratorContext => ComplexTypeSupport = _ => Map.empty
      override val getSimpleTypeSupport: GeneratorContext => SimpleTypeSupport = f
    }
  }

  def emptyObjectAsJsObject: CustomTypeSupport = complex { _ => {
    case (obj: ObjectDefinition, _) if obj.properties.isEmpty =>
      TypeSupport(
        tpe = RootClass.newClass("play.api.libs.json.JsObject"),
        fullQualified = RootClass.newClass("play.api.libs.json.JsObject"),
        defs = Nil
      )
  }}

  /**
    * Use org.joda.time.LocalDate for ''date'' type
    *
    * @param pattern read/write date format pattern (default is "yyyy-MM-dd")
    * @param corrector a simple string transformation function that can be used to transform input String before parsing.
    * @param readsClass a class where default JSON readers placed (<= 2.5.x: play.api.libs.json.Reads, 2.6.x: play.api.libs.json.JodaReads)
    * @param writesClass a class where default JSON writers placed (<= 2.5.x: play.api.libs.json.Writes, 2.6.x: play.api.libs.json.JodaWrites)
    */
  private def internalJodaLocalDateSupport(pattern: String, corrector: Tree, readsClass: ClassSymbol, writesClass: ClassSymbol): CustomTypeSupport =
    simple { _ => {
      case _: DateDefinition =>
        val LocalDateClass = RootClass.newClass("org.joda.time.LocalDate")
        val defs = TypeSupportDefs(
          symbol = LocalDateClass,
          definition = EmptyTree,
          jsonReads  = {
            val readsType = RootClass.newClass("Reads") TYPE_OF LocalDateClass
            VAL("JodaLocalDateReads", readsType) withFlags (Flags.IMPLICIT, Flags.LAZY) := {
              readsClass DOT "jodaLocalDateReads" APPLY(LIT(pattern), corrector)
            }
          },
          jsonWrites = {
            val writesType = RootClass.newClass("Writes") TYPE_OF LocalDateClass
            VAL("JodaLocalDateWrites", writesType) withFlags (Flags.IMPLICIT, Flags.LAZY) := {
              writesClass DOT "jodaLocalDateWrites" APPLY LIT(pattern)
            }
          },
          queryBindable = {
            val formatter = RootClass.newClass("org.joda.time.format.DateTimeFormat") DOT "forPattern" APPLY LIT(pattern)
            val parent = RootClass.newClass("QueryStringBindable") DOT "Parsing" APPLYTYPE LocalDateClass APPLY (
              LAMBDA(PARAM("s").tree) ==> { LocalDateClass DOT "parse" APPLY(REF("s"), formatter) },
              LAMBDA(PARAM("d").tree) ==> { REF("d") DOT "toString" APPLY LIT(pattern) },
              LAMBDA(PARAM("key", StringClass).tree, PARAM("e", RootClass.newClass("Exception")).tree) ==> {
                LIT(s"Cannot parse parameter %s as date with pattern '$pattern': %s") DOT "format" APPLY(REF("key"), REF("e") DOT "getMessage")
              }
            )
            OBJECTDEF("JodaLocalDateQueryStringBindable")
              .withFlags(Flags.IMPLICIT)
              .withParents(parent)
              .tree
          },
          pathBindable  = EmptyTree,
          queryParameter = {
            val queryParameterType = RootClass.newClass("QueryParameter") TYPE_OF LocalDateClass
            VAL(s"JodaLocalDateQueryParameter", queryParameterType) withFlags(Flags.IMPLICIT, Flags.LAZY) := {
              RootClass.newClass("QueryParameter") DOT "jodaLocalDateParameter" APPLY LIT(pattern)
            }
          },
          pathParameter = EmptyTree
        )
        TypeSupport(LocalDateClass, LocalDateClass, defs :: Nil)
    }}

  def jodaLocalDateSupport(pattern: String = "yyyy-MM-dd", corrector: Tree = REF("identity")): CustomTypeSupport =
    internalJodaLocalDateSupport(pattern = pattern, corrector = corrector, readsClass = RootClass.newClass("Reads"), writesClass = RootClass.newClass("Writes"))

  def jodaLocalDateSupport26(pattern: String = "yyyy-MM-dd", corrector: Tree = REF("identity")): CustomTypeSupport =
    internalJodaLocalDateSupport(pattern = pattern, corrector = corrector, readsClass = RootClass.newClass("JodaReads"), writesClass = RootClass.newClass("JodaWrites"))

  /**
    * Use org.joda.time.DateTime for ''date-time'' type
    *
    * @param pattern read/write date-time format pattern (default is "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    * @param corrector a simple string transformation function that can be used to transform input String before parsing.
    * @param readsClass a class where default JSON readers placed (<= 2.5.x: play.api.libs.json.Reads, 2.6.x: play.api.libs.json.JodaReads)
    * @param writesClass a class where default JSON writers placed (<= 2.5.x: play.api.libs.json.Writes, 2.6.x: play.api.libs.json.JodaWrites)
    */
  private def internalJodaDateTimeSupport(pattern: String, corrector: Tree, readsClass: ClassSymbol, writesClass: ClassSymbol): CustomTypeSupport =
    simple { _ => {
      case _: DateTimeDefinition =>
        val DateTimeClass = RootClass.newClass("org.joda.time.DateTime")
        val defs = TypeSupportDefs(
          symbol = DateTimeClass,
          definition = EmptyTree,
          jsonReads  = {
            val readsType = RootClass.newClass("Reads") TYPE_OF DateTimeClass
            VAL("JodaDateTimeReads", readsType) withFlags (Flags.IMPLICIT, Flags.LAZY) := {
              readsClass DOT "jodaDateReads" APPLY(LIT(pattern), corrector)
            }
          },
          jsonWrites = {
            val writesType = RootClass.newClass("Writes") TYPE_OF DateTimeClass
            VAL("JodaDateTimeWrites", writesType) withFlags (Flags.IMPLICIT, Flags.LAZY) := {
              writesClass DOT "jodaDateWrites" APPLY LIT(pattern)
            }
          },
          queryBindable = {
            val formatter = RootClass.newClass("org.joda.time.format.DateTimeFormat") DOT "forPattern" APPLY LIT(pattern)
            val parent = RootClass.newClass("QueryStringBindable") DOT "Parsing" APPLYTYPE DateTimeClass APPLY (
              LAMBDA(PARAM("s").tree) ==> { DateTimeClass DOT "parse" APPLY(REF("s"), formatter) },
              LAMBDA(PARAM("d").tree) ==> { REF("d") DOT "toString" APPLY LIT(pattern) },
              LAMBDA(PARAM("key", StringClass).tree, PARAM("e", RootClass.newClass("Exception")).tree) ==> {
                LIT(s"Cannot parse parameter %s as date-time with pattern '$pattern': %s") DOT "format" APPLY(REF("key"), REF("e") DOT "getMessage")
              }
            )
            OBJECTDEF("JodaDateTimeQueryStringBindable")
              .withFlags(Flags.IMPLICIT)
              .withParents(parent)
              .tree
          },
          pathBindable = EmptyTree,
          queryParameter = {
            val queryParameterType = RootClass.newClass("QueryParameter") TYPE_OF DateTimeClass
            VAL(s"JodaDateTimeQueryParameter", queryParameterType) withFlags(Flags.IMPLICIT, Flags.LAZY) := {
              RootClass.newClass("QueryParameter") DOT "jodaDateTimeParameter" APPLY LIT(pattern)
            }
          },
          pathParameter = EmptyTree
        )
        TypeSupport(DateTimeClass, DateTimeClass, defs :: Nil)
    }}

  def jodaDateTimeSupport(pattern: String = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", corrector: Tree = REF("identity")): CustomTypeSupport =
    internalJodaDateTimeSupport(pattern = pattern, corrector = corrector, readsClass = RootClass.newClass("Reads"), writesClass = RootClass.newClass("Writes"))

  def jodaDateTimeSupport26(pattern: String = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", corrector: Tree = REF("identity")): CustomTypeSupport =
    internalJodaDateTimeSupport(pattern = pattern, corrector = corrector, readsClass = RootClass.newClass("JodaReads"), writesClass = RootClass.newClass("JodaWrites"))

}
