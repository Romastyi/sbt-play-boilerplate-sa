package play.boilerplate.generators

import play.boilerplate.parser.model._

class PlayModelGeneratorParser extends CodeGenerator {

  import treehugger.forest._
  import treehuggerDSL._

  override def generate(schema: Schema)(implicit ctx: GeneratorContext): Iterable[CodeFile] = {

    val init = EmptyTree inPackage ctx.modelPackageName

    for {
      (name, model) <- schema.definitions
    } yield SourceCodeFile(
      className = name.capitalize,
      header = treeToString(init),
      impl = treeToString(generateClass(model)(ctx.setInModel(true)): _ *)
    )

  }

  private def generateClass(model: Model)(implicit ctx: GeneratorContext): Seq[Tree] = {
    GeneratorUtils.getTypeSupport(model.ref).definitions
  }

}
