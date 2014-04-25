package scala.reflect

import scala.language.experimental.{macros => prettyPlease}
import org.scalareflect.invariants._
import org.scalareflect.adt._

/*
list of ugliness discovered so far
1. function parameters can not be annotated
2. try expr catch expr syntax
3. package objects don't have annotations
4. lambdas can not be disambiguated from self type in template
5. patterns in vals are parsed inconsistently
7. awkward meaning of infix patterns: `a infix (b, c)` means `infix(a, b, c)`
8. pq"_: F[_]" is something completely different from pq"_: F[_ >: lo <: hi]"
9. pq"a[b]" is a legal pattern
10. guess what pq"a -1" means
11. no way to fully qualify things that are in empty package
12. vars with default values may not contain patterns
*/

// (Together) TODO: tree-based symbols and types (see https://github.com/paulbutcher/implementor/blob/f1921de2b7de3d5ea8cf7f230c8e4e9f8c7f4b26/core/src/main/scala/org/scalamock/Implement.scala)
// (Together) TODO: .tpe vs .signature?
// (Together) TODO: collection-like methods (see http://clang.llvm.org/docs/LibASTMatchersReference.html)
// (Together) TODO: rewriting/transformation methods
// (Together) TODO: decide on entry point tree (compilation unit? package?; use-cases compile-time, runtime, presentation)
// (Together) TODO: add tree-specific equalities as ref_==, =:= etc
// (Denys)    TODO: add moar requires
// (Denys)    TODO: think about requiring ident values to be non-keyword
// (Denys)    TODO: parser
// (Together) TODO: trivia: whitespace, comments, etc (see http://msdn.microsoft.com/en-us/vstudio/hh500769)
// (Together) TODO: history vs positions (can trivia be inferred from positions only?)
// (Denys)    TODO: unhygienic quasiquotes
// (Denys)    TODO: consider adding default values for case class fields whenever applicable
// (Eugene)   TODO: pretty printer
// (Together) TODO: figure out which apis need to be moved to subclasses and which apis (Tree.annots, Type.typCtor, Type.args, Symbol.companion, Type.companion) need to be added for convenience
// (Together) TODO: implement scaladoc with palladium

// TODO: converter: double check conversion of `(f _)(x)` (bug 46)

package core {

  @root trait Tree extends cbc.Trees.Tree {
    def parent: Option[Tree] = ???
    override def equals(other: Any): Boolean = ???
    override def hashCode(): Int = ???
  }

  @branch trait Ref extends Tree {
    def sym: Symbol = ???
  }

  @branch trait Ident extends Ref {
    def value: String
    def isBackquoted: Boolean
    def isRightAssocOp: Boolean = value.last != ':'
    def isUnaryOp: Boolean = unaryOps contains value
    def isAssignmentOp = value match {
      case "!=" | "<=" | ">=" | "" => false
      case _                       => value.last == '=' && value.head != '=' && isOperatorPart(value.head)
    }
    // opPrecedence?
    def precedence: Int =
      if (isAssignmentOp) 0
      else if (isScalaLetter(value.head)) 1
      else (value.head: @scala.annotation.switch) match {
        case '|'             => 2
        case '^'             => 3
        case '&'             => 4
        case '=' | '!'       => 5
        case '<' | '>'       => 6
        case ':'             => 7
        case '+' | '-'       => 8
        case '*' | '/' | '%' => 9
        case _               => 10
      }
    def isVarPattern: Boolean = this match {
      case _: Term.Ident => !isBackquoted && value.head.isLower && value.head.isLetter
      case _             => false
    }
    def isInterpolationId: Boolean = ???

    private def isOperatorPart(ch: Char) = ???
    private def isScalaLetter(ch: Char) = ???
  }

  @branch trait Term extends Arg with Stmt.Template with Stmt.Block with Aux.Catch {
    def tpe: Type = ???
  }
  object Term {
    @branch trait Ref extends Term with core.Ref {
      def isPath: Boolean = isStableId || this.isInstanceOf[This]
      def isQualId: Boolean = this match {
        case _: Ident             => true
        case Select(qual: Ref, _) => qual.isQualId
        case _                    => false
      }
      def isStableId: Boolean = this match {
        case _: Ident | _: SuperSelect => true
        case Select(qual: Ref, _)      => qual.isPath
        case _                         => false
      }
    }
    @leaf class This(qual: Option[core.Ident]) extends Ref
    @leaf class Ident(value: scala.Predef.String @nonEmpty, isBackquoted: Boolean = false) extends core.Ident with Ref with Pat with Symbol {
      require(!keywords.contains(value) || isBackquoted)
      def isBinding: Boolean = ???
      def annots: List[Annot] = Nil
    }
    @leaf class SuperSelect(qual: Option[core.Ident], supertyp: Option[Type.Ident], selector: Term.Ident) extends Ref
    @leaf class Select(qual: Term, selector: Term.Ident) extends Ref with Pat

    @leaf class Interpolate(prefix: Ident, parts: List[Lit.String] @nonEmpty, args: List[Term]) extends Term {
      require(prefix.isInterpolationId)
      require(parts.length == args.length + 1)
    }
    @leaf class Apply(fun: Term, args: List[Arg]) extends Term
    @leaf class ApplyType(fun: Term, args: List[Type] @nonEmpty) extends Term
    @leaf class ApplyRight(lhs: Term, op: Ident, targs: List[Type], rhs: Term) extends Term {
      require(op.isRightAssocOp)
    }
    @leaf class ApplyUnary(op: Ident, arg: Term) extends Term {
      require(op.isUnaryOp)
    }
    @leaf class Assign(lhs: Term.Ref, rhs: Term) extends Term
    @leaf class Update(lhs: Apply, rhs: Term) extends Term
    @leaf class Return(expr: Term) extends Term
    @leaf class Throw(expr: Term) extends Term
    @leaf class Ascribe(expr: Term, typ: Type) extends Term
    @leaf class Annotate(expr: Term, annots: List[Annot] @nonEmpty) extends Term with HasAnnots
    @leaf class Tuple(elements: List[Term] @nonEmpty) extends Term
    @leaf class Block(stats: List[Stmt.Block]) extends Term
    @leaf class If(cond: Term, thenp: Term, elsep: Term) extends Term
    @leaf class Match(scrut: Term, cases: Aux.Cases @nonEmpty) extends Term
    @leaf class Try(expr: Term, catchp: Option[Aux.Catch], finallyp: Option[Term]) extends Term
    @leaf class Function(params: List[Param.Function], body: Term) extends Term {
      require(params.length == 1 || !params.exists(_.annots.contains(Annot.Implicit)))
    }
    @leaf class PartialFunction(cases: Aux.Cases) extends Term
    @leaf class While(expr: Term, body: Term) extends Term
    @leaf class Do(body: Term, expr: Term) extends Term
    @leaf class For(enums: List[Enum] @nonEmpty, body: Term) extends Term {
      require(enums.head.isInstanceOf[Enum.Generator])
    }
    @leaf class ForYield(enums: List[Enum] @nonEmpty, body: Term) extends Term
    @leaf class New(templ: Aux.Template) extends Term
    // (Denys) TODO: might need additional validation
    @leaf class Placeholder() extends Term
    @leaf class Eta(term: Term) extends Term
  }

  @branch trait Type extends Tree {
    def <:< (other: Type): Boolean = ???
    def weak_<:<(other: Type): Boolean = ???
    def widen: Type = ???
    def dealias: Type = ???
    def erasure: Type = ???
    // TODO: can we somehow inherit certain flags from type symbols here, s.t. we can write `t"T".isCaseClass`?
    // TODO: simple type validation
  }
  object Type {
    @branch trait Ref extends Type with core.Ref
    @leaf class Ident(value: String @nonEmpty, isBackquoted: Boolean = false) extends core.Ident with Ref {
      require(!keywords.contains(value) || isBackquoted)
    }
    @leaf class Select(qual: Term.Ref, name: Type.Ident) extends Ref {
      require(qual.isPath)
    }
    @leaf class SuperSelect(qual: Option[core.Ident], supertyp: Option[Type.Ident], selector: Type.Ident) extends Ref
    @leaf class Project(qual: Type, name: Type.Ident) extends Ref
    @leaf class Singleton(ref: Term.Ref) extends Ref {
      require(ref.isPath)
    }

    // TODO: validate that typ can actually be applied
    @leaf class Apply(typ: Type, args: List[Type] @nonEmpty) extends Type
    @leaf class Function(params: List[Type], res: Type) extends Type
    @leaf class Tuple(elements: List[Type] @nonEmpty) extends Type
    @leaf class Compound(typs: List[Type], refinement: List[Stmt.Refine] @nonEmpty) extends Type
    @leaf class Existential(typ: Type, quants: List[Stmt.Existential] @nonEmpty) extends Type
    @leaf class Annotate(typ: Type, annots: List[Annot] @nonEmpty) extends Type with HasAnnots
    // (Denys) TODO: need to validate that placeholder appears within one of allowed contexts (e.g. `type T = _` is illegal)
    @leaf class Placeholder(bounds: Aux.TypeBounds) extends Type
  }

  @branch trait Pat extends Tree {
    // TODO: how should tpe look like? inTpe/outTpe?
  }
  object Pat {
    @leaf class Wildcard() extends Pat
    @leaf class SeqWildcard() extends Pat
    @leaf class Bind(lhs: Term.Ident, rhs: Pat) extends Pat
    @leaf class Alternative(lhs: Pat, rhs: Pat) extends Pat
    @leaf class Tuple(elements: List[Pat] @nonEmpty) extends Pat
    @leaf class Extract(ref: Term.Ref, targs: List[Type], elements: List[Pat]) extends Pat {
      require(ref.isStableId)
    }
    @leaf class Interpolate(prefix: Term.Ident, parts: List[Lit.String] @nonEmpty, args: List[Pat]) extends Pat {
      require(prefix.isInterpolationId)
      require(parts.length == args.length + 1)
    }
    @leaf class Typed(lhs: Pat, rhs: Type) extends Pat {
      require(lhs.isInstanceOf[Pat.Wildcard] || lhs.isInstanceOf[Term.Ident])
    }
  }

  @branch trait Symbol extends Tree with HasAnnots {
    def owner: Symbol = ???
    def ref: core.Ref = ???
    def overrides: List[Symbol] = ???
    // TODO: knownDirectSubclasses => can we replace this with an optimized query?
    // TODO: methods from Scope. do we need them here? how do we deduplicate wrt types?
  }
  object Symbol {
    @branch trait Field extends Symbol
    @branch trait Val extends Field
    @branch trait Var extends Field
    @branch trait Def extends Symbol
    @branch trait Type extends Symbol
    @branch trait Template extends Symbol with Stmt.TopLevel
  }

  @branch trait Decl extends Symbol with Stmt.Template with Stmt.Refine
  object Decl {
    @leaf class Val(annots: List[Annot], pats: List[Term.Ident] @nonEmpty,
                    typ: core.Type) extends Decl with Stmt.Existential with Symbol.Val
    @leaf class Var(annots: List[Annot], pats: List[Term.Ident] @nonEmpty,
                    typ: core.Type) extends Decl with Symbol.Var
    @leaf class Def(annots: List[Annot], name: Term.Ident, tparams: List[TypeParam.Def],
                    paramss: List[List[Param.Def]], implicits: List[Param.Def],
                    typ: Option[core.Type]) extends Decl with Symbol.Def
    @leaf class Type(annots: List[Annot], name: core.Type.Ident, tparams: List[TypeParam.Type],
                     bounds: Aux.TypeBounds) extends Decl with Stmt.Existential with Symbol.Type
  }

  @branch trait Defn extends Symbol with Stmt.Block with Stmt.Template
  object Defn {
    @leaf class Val(annots: List[Annot], pats: List[Pat] @nonEmpty,
                    typ: Option[core.Type], rhs: Term) extends Defn with Symbol.Val
    @leaf class Var(annots: List[Annot], pats: List[Pat] @nonEmpty,
                    typ: Option[core.Type], rhs: Option[Term]) extends Defn with Symbol.Var {
      require(rhs.nonEmpty || pats.forAll(_.isInstanceOf[Term.Ident]))
      require(typ.nonEmpty || rhs.nonEmpty)
    }
    @leaf class Def(annots: List[Annot], name: Term.Ident, tparams: List[TypeParam.Def],
                    paramss: List[List[Param.Def]], implicits: List[Param.Def],
                    typ: Option[core.Type], body: Term) extends Defn with Symbol.Def
    @leaf class Macro(annots: List[Annot], name: Term.Ident, tparams: List[TypeParam.Def],
                      paramss: List[List[Param.Def]], implicits: List[Param.Def],
                      typ: Type, body: Term) extends Defn
    @leaf class Type(annots: List[Annot], name: core.Type.Ident, tparams: List[TypeParam.Type],
                     body: Type) extends Defn with Stmt.Refine with Symbol.Type
    @leaf class Class(annots: List[Annot], name: core.Type.Ident, tparams: List[TypeParam.Def],
                      val ctor: Option[Ctor.Primary], templ: Aux.Template)
                      extends Defn with Symbol.Template {
      def companion: Option[Object] = ???
    }
    @leaf class Trait(annots: List[Annot], name: core.Type.Ident, tparams: List[TypeParam.Type],
                      templ: Aux.Template) extends Defn with Symbol.Template {
      def isInterface: Boolean = templ.stats.forall(_.isInstanceOf[Decl])
      def companion: Option[Object] = ???
    }
    @leaf class Object(annots: List[Annot], name: Term.Ident, templ: Aux.Template)
                       extends Defn with Symbol.Template {
      def companion: Option[Defn] = ??? // TODO: Class | Trait
    }
  }

  @branch trait Ctor extends Symbol {
    def paramss: List[List[Param.Def]]
    def implicits: List[Param.Def]
  }
  object Ctor {
    @leaf class Primary(annots: List[Annot], paramss: List[List[Param.Def]] = Nil, implicits: List[Param.Def] = Nil) extends Ctor
    @leaf class Secondary(annots: List[Annot], paramss: List[List[Param.Def]], implicits: List[Param.Def],
                          primaryCtorArgss: List[List[Term]]) extends Ctor with Stmt.Template
  }

  object Stmt {
    @branch trait TopLevel extends Tree
    @branch trait Template extends Block
    @branch trait Block extends Refine
    @branch trait Refine extends Existential
    @branch trait Existential extends Tree
  }

  @branch trait Scope extends Tree {
    // TODO: design scopes
    // should include parents, self, members, methods, types, etc
    // where things like methods should be Iterable[Symbol] + have apply(ident) and apply(symbol)
  }

  @branch trait Lit extends Term with Pat
  object Lit {
    // TODO: maybe add overloaded apply(value)
    // TODO: maybe add unapply(lit): Option[Any]
    @branch trait Bool extends Lit
    @leaf class True() extends Bool
    @leaf class False() extends Bool
    @leaf class Int(value: scala.Int) extends Lit with Type
    @leaf class Long(value: scala.Long) extends Lit with Type
    @leaf class Float(value: scala.Float) extends Lit with Type
    @leaf class Double(value: scala.Double) extends Lit with Type
    @leaf class Char(value: scala.Char) extends Lit with Type
    @leaf class String(value: Predef.String) extends Lit with Type
    // TODO: not all symbols are representable as literals, e.g. scala.Symbol("")
    @leaf class Symbol(value: scala.Symbol) extends Lit with Type
    @leaf class Null() extends Lit
    @leaf class Unit() extends Lit
  }

  @leaf class Import(clauses: List[Import.Clause] @nonEmpty) extends Stmt.TopLevel with Stmt.Template with Stmt.Block
  object Import {
    // TODO: validate that wildcard import can only be the last one in the list of sels
    @leaf class Clause(ref: Term.Ref, sels: List[Selector] @nonEmpty) extends Tree {
      require(ref.isStableId)
    }

    @branch trait Selector extends Tree
    object Selector {
      @leaf class Wildcard() extends Selector
      // TODO: needs some kind of idents here but they can neither be term nor type
      @leaf class Name(name: String) extends Selector
      @leaf class Rename(from: String, to: String) extends Selector
      @leaf class Unimport(name: String) extends Selector
    }
  }

  @branch trait Arg extends Tree
  object Arg {
    @leaf class Named(name: Term.Ident, arg: Term) extends Arg
    @leaf class Seq(arg: Term) extends Arg
  }

  @branch trait Enum extends Tree
  object Enum {
    @leaf class Generator(pat: Pat, rhs: Term) extends Enum
    @leaf class Val(pat: Pat, rhs: Term) extends Enum
    @leaf class Guard(cond: Term) extends Enum
  }

  @branch trait Param extends Symbol
  object Param {
    @leaf class Function(annots: List[Annot] = Nil, name: Option[Term.Ident] = None, typ: Option[Type] = None) extends Param
    @leaf class Def(annots: List[Annot] = Nil, name: Option[Term.Ident] = None, typ: Type, default: Option[Term] = None) extends Param
  }

  @branch trait TypeParam extends Symbol
  object TypeParam {
    @leaf class Def(annots: List[Annot] = Nil,
                    name: Option[core.Type.Ident] = None,
                    tparams: List[TypeParam.Type] = Nil,
                    contextBounds: List[core.Type] = Nil,
                    viewBounds: List[core.Type] = Nil,
                    bounds: Aux.TypeBounds = Aux.TypeBounds.empty) extends TypeParam
    @leaf class Type(annots: List[Annot] = Nil,
                     name: Option[core.Type.Ident] = None,
                     tparams: List[TypeParam.Type] = Nil,
                     bounds: Aux.TypeBounds = Aux.TypeBounds.empty) extends TypeParam
  }

  @branch trait HasAnnots extends Tree {
    def annots: List[Annot]
    // (Eugene) TODO: https://docs.google.com/spreadsheet/ccc?key=0Ahw_zqMtW4nNdC1lRVJvc3VjTUdOX0ppMVpSYzVRSHc&usp=sharing#gid=0
    // * write a script that fetches this google doc and converts it into a, say, CSV spec
    // * write a test that validates the spec by generating source files and parsing them
    // * write a macro that generates implementation of validateAnnots from the spec + extension methods like isImplicit
    private[reflect] def validateAnnots(enclosing: Tree): Boolean = ???
  }
  @branch trait Annot extends Tree
  object Annot {
    @branch trait Transient extends Annot
    // (Together) TODO: design the attachment API

    @branch trait Source extends Annot
    @leaf class UserDefined(tpe: Type, argss: List[List[Arg]]) extends Source
    @leaf class Doc(doc: String) extends Source // TODO: design representation for scaladoc

    @branch trait Mod extends Source
    @leaf class Private(within: String) extends Mod // TODO: design a name resolution API for these and imports
    @leaf class Protected(within: String) extends Mod
    @leaf class Implicit() extends Mod
    @leaf class Final() extends Mod
    @leaf class Sealed() extends Mod
    @leaf class Override() extends Mod
    @leaf class Case() extends Mod
    @leaf class Abstract() extends Mod
    @leaf class Covariant() extends Mod
    @leaf class Contravariant() extends Mod
    @leaf class Lazy() extends Mod
    @leaf class AbstractOverride() extends Mod

    @branch trait Param extends Source
    @leaf class ByName() extends Param
    @leaf class Vararg() extends Param
    @leaf class Val() extends Param
    @leaf class Var() extends Param
  }

  object Aux {
    @branch trait Catch extends Tree
    @leaf class Case(pat: Pat, cond: Option[Term] = None, body: Term = Lit.Unit()) extends Tree
    @leaf class Cases(cases: List[Case] @nonEmpty) extends Catch
    // TODO: validate that tpe is one of the class types
    @leaf class Parent(tpe: Type, argss: List[List[Term]] = Nil) extends Ref
    @leaf class Template(early: List[Defn.Val] = Nil, parents: List[Parent] = Nil,
                         self: Self = Self.empty, stats: List[Stmt.Template] = Nil) extends Tree {
      require(parents.isEmpty || !parents.tail.exists(_.argss.nonEmpty))
    }
    @leaf class Self(name: Option[Term.Ident] = None, typ: Option[Type] = None) extends Symbol {
      def annots: List[Annot] = Nil
    }
    @leaf class TypeBounds(lo: Option[Type] = None, hi: Option[Type] = None) extends Tree
  }

  @branch trait Package extends Tree { def stats: List[Stmt.TopLevel] }
}

package object core {
  object Package {
    @leaf class Empty(stats: List[Stmt.TopLevel]) extends Package
    @leaf class Named(name: Term.Ref, stats: List[Stmt.TopLevel]) extends Package with Stmt.TopLevel {
      require(name.isQualId)
    }
    @leaf class Object(annots: List[Annot], name: Term.Ident, templ: Aux.Template) extends Package with Stmt.TopLevel
  }

  val keywords = Set(
    "abstract", "case", "do", "else", "finally", "for", "import", "lazy",
    "object", "override", "return", "sealed", "trait", "try", "var", "while",
    "catch", "class", "extends", "false", "forSome", "if", "match", "new",
    "package", "private", "super", "this", "true", "type", "with", "yield",
    "def", "final", "implicit", "null", "protected", "throw", "val", "_",
    ":", "=", "=>", "<-", "<:", "<%", ">:", "#", "@", "\u21D2", "\u2190"
  )
  val unaryOps = Set("-", "+", "~", "!")
  implicit class Quasiquotes(ctx: StringContext) {
    protected trait api {
      def apply[T](args: T*): Tree = macro ???
      def unapply(scrutinee: Any): Any = macro ???
    }
    object q extends api
    object tq extends api
    object cq extends api
    object pq extends api
    object fq extends api
  }
  implicit class RichParents(val parents: List[Aux.Parent]) {
    // TODO: not sure whether this is the best approach
    def linearization: List[Aux.Parent] = ???
  }
  def lub(typs: Type*): Type = ???
  def glb(typs: Type*): Type = ???
}
