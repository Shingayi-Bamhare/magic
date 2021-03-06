;; this is magic
(ns magic.core
  (:refer-clojure :exclude [compile])
  (:require [mage.core :as il]
            [clojure.string :as string])
  (:import [clojure.lang RT Numbers Compiler LineNumberingTextReader
            Symbol Namespace IFn Var Keyword Symbol
            IPersistentList LazySeq IPersistentVector IPersistentMap IPersistentSet
            PersistentArrayMap PersistentHashSet PersistentList PersistentVector]
           [clojure.lang.CljCompiler.Ast RHC ParserContext
            Expr LiteralExpr StaticMethodExpr InstanceMethodExpr StaticPropertyExpr NumberExpr
            InstancePropertyExpr InstanceFieldExpr MapExpr VarExpr TheVarExpr InvokeExpr HostExpr
            FnExpr FnMethod BodyExpr LocalBindingExpr IfExpr VectorExpr NewExpr LetExpr CaseExpr
            MonitorEnterExpr MonitorExitExpr InstanceZeroArityCallExpr StaticFieldExpr InstanceOfExpr
            ThrowExpr TryExpr TryExpr+CatchClause UnresolvedVarExpr EmptyExpr SetExpr ImportExpr RecurExpr
            KeywordInvokeExpr KeywordExpr]
           [System.IO FileInfo Path]
           [System.Threading Monitor]
           [System.Reflection TypeAttributes MethodAttributes FieldAttributes]
           AppDomain
           System.Reflection.Emit.OpCodes
           AssemblyName
           AssemblyBuilderAccess))

(defn field-map
  "Get a map of all of an object's fields. Reflects."
  [obj]
  (-> obj
      .GetType
      (.GetFields (enum-or BindingFlags/Instance BindingFlags/NonPublic BindingFlags/Public))
      (->> (mapcat #(vector (keyword (.Name %))
                            (.GetValue % obj)))
           (apply hash-map))))

(defn property-map
  "Get a map of all of an object's properties. Reflects."
  [obj]
  (-> obj
      .GetType
      (.GetProperties (enum-or BindingFlags/Instance BindingFlags/NonPublic BindingFlags/Public))
      (->> (mapcat #(vector (keyword (.Name %))
                            (try (.GetValue % obj nil)
                              (catch Exception e nil))))
           (apply hash-map))))

(defn data-map
  "Get a map of all of an object's fields and properties. Reflects."
  [obj]
  (cond
    (nil? obj) obj
    (.IsValueType (type obj)) obj
    (instance? System.Collections.IEnumerable obj) (map data-map obj)
    :else (merge {::type (type obj)}
                 (field-map obj)
                 (property-map obj))))

(defn analyze
  ([form] (analyze form RHC/Expression))
  ([form rhc] (Compiler/Analyze (ParserContext. rhc) form)))

(defn find-method
  ([type name & params] (.GetMethod type name (into-array Type params))))

(defn find-constructor
  ([type & params] (.GetConstructor type (into-array Type params))))

(defn property-getter [type name]
  (find-method type (str "get_" name)))

(defn property-setter [type name]
  (find-method type (str "set_" name)))

(def genstr (comp str gensym str))

(defn find-file [ns]
  (-> ns
      name
      (string/replace "." "/")
      (str ".clj")
      RT/FindFile
      ))

;; http://stackoverflow.com/questions/24922478/is-there-a-way-to-read-all-the-forms-in-a-clojure-file
(defn read-all
  [file]
  (let [rdr (PushbackTextReader. (.OpenText file))]
    (loop [forms []]
      (let [form (try (read rdr) (catch Exception e nil))]
        (if form
          (recur (conj forms form))
          (do (.Close rdr)
            forms))))))

(defn append [a col]
  (concat col [a]))

(defn load-argument [i]
  (cond
    (= i 0) (il/ldarg-0)
    (= i 1) (il/ldarg-1)
    (= i 2) (il/ldarg-2)
    (= i 3) (il/ldarg-3)
    :else (il/ldarg i)))

;; bah! super gross because ClrType is not safe to call AST nodes... 
(defn clr-type [e]
  (cond
    ;; BUG in TryExpr analysis. ClrType always reported as type of try expession
    ;; but this is not accurate
    (= (type e) TryExpr)
    (let [{:keys [_tryExpr _catchExprs]} (data-map e)
          try-expr-type (clr-type _tryExpr)
          catch-handler-types (map #(clr-type (-> % data-map :_handler)) _catchExprs)]
      (if (= 1 (count (into #{try-expr-type} catch-handler-types)))
        try-expr-type
        Object))
    
    (isa? (type e) Expr)
    (try
      (.ClrType e)
      (catch System.Exception e
        Object))
    :else (type e)))

(def load-constant)

;; TODO overflows?
;; can overflow opcodes replace e.g. RT.intCast?
(def intrinsic-conv
  {Char   (il/conv-u2)
   SByte  (il/conv-i1)
   Byte   (il/conv-u1)
   Int16  (il/conv-i2)
   Int32  (il/conv-i4)
   Int64  (il/conv-i8)
   Double (il/conv-r8)
   Single (il/conv-r4)
   UInt16 (il/conv-u2)
   UInt32 (il/conv-u4)
   UInt64 (il/conv-u8)})

(defn convert [from to]
  (let [from-type (cond
                    (nil? from)             Object
                    (isa? (type from) Type) from
                    (isa? (type from) Expr) (clr-type from)
                    :else                   (type from))]
    (cond
      (or (nil? from) (nil? from-type))
      nil
      
      ;; do nothing if the types are the same 
      (= from-type to)
      nil
      
      ;; cannot convert nil to value type
      (and (nil? from) (.IsValueType to))
      (throw (Exception. (str "Cannot convert nil to value type " to)))
      
      ;; do nothing for nil to non value type 
      (nil? from)
      nil
      
      ;; convert void to nil
      ;; TODO is this a terrible idea?
      (and (= System.Void from-type) (not (.IsValueType to)))
      (il/ldnull)
      
      (and (= System.Void from-type) (.IsValueType to))
      (throw (Exception. (str "Cannot convert void to value type " to)))
      
      ;; use user defined implicit conversion if it exists
      (find-method from-type "op_Implicit" to)
      (il/call (find-method from-type "op_Implicit" to))
      
      ;; use user defined explicit conversion if it exists
      (find-method from-type "op_Explicit" to)
      (il/call (find-method from-type "op_Explicit" to))
      
      ;; use intrinsic conv opcodes from primitive to primitive
      (and (.IsPrimitive from-type) (.IsPrimitive to))
      (intrinsic-conv to)
      
      ;; box valuetypes to objects
      (and (.IsValueType from-type) (= to Object))
      (il/box from-type)
      
      ;; unbox objects to valuetypes
      (and (= from-type Object) (.IsValueType to))
      (il/unbox-any to)
      
      ;; castclass if to is a subclass of from
      (.IsSubclassOf to from-type)
      (il/castclass to)
      
      ;; do nothing if converting to super class
      (.IsSubclassOf from-type to)
      nil
      
      :else
      (throw (Exception. (str "Cannot convert " from-type " to " to))))))


(defn load-vector
  ([v] (load-vector v load-constant))
  ([v f]
   [(load-constant (int (count v)))
    (il/newarr Object)
    (map (fn [i c]
           [(il/dup)
            (load-constant (int i))
            (f c)
            (convert c Object)
            (il/stelem-ref)])
         (range)
         v)
    (il/call (find-method clojure.lang.RT "vector" |System.Object[]|))]))

(defn load-set
  ([v] (load-set v load-constant))
  ([v f]
   [(load-constant (int (count v)))
    (il/newarr Object)
    (map (fn [i c]
           [(il/dup)
            (load-constant (int i))
            (f c)
            (convert c Object)
            (il/stelem-ref)])
         (range)
         v)
    (il/call (find-method clojure.lang.RT "set" |System.Object[]|))]))

(defn load-list
  ([v] (load-list v load-constant))
  ([v f]
   [(load-constant (int (count v)))
    (il/newarr Object)
    (map (fn [i c]
           [(il/dup)
            (load-constant (int i))
            (f c)
            (convert c Object)
            (il/stelem-ref)])
         (range)
         v)
    (il/call (find-method clojure.lang.PersistentList "create" |System.Object[]|))]))

(defn load-map [keyvals]
  (let [ks (take-nth 2 keyvals)
        vs (take-nth 2 (drop 1 keyvals))]
    [(load-constant (int (+ (count ks) (count vs))))
     (il/newarr Object)
     (map (fn [i kv]
            [(il/dup)
             (load-constant (int i))
             (load-constant kv)
             ; (cast-if-different kv Object)
             (il/stelem-ref)])
          (range)
          (interleave ks vs))
     (il/call (find-method clojure.lang.PersistentArrayMap "createWithCheck" |System.Object[]|))]))

(defn load-keyword [k]
  (let [ns  (.. k Namespace)
        name (.. k Name)]
    [(load-constant ns)
     (load-constant name)
     (il/call (find-method Keyword "intern" String String))]))

(defn load-symbol [k]
  (let [ns  (.. k Namespace)
        name (.. k Name)]
    [(load-constant ns)
     (load-constant name)
     (il/call (find-method Symbol "intern" String String))]))

(defn load-var [v]
  (let [nsname  (.. v Namespace Name ToString)
        symname (.. v Symbol ToString)]
    [(load-constant nsname)
     (load-constant symname)
     (il/call (find-method RT "var" String String))]))

;; NOTE the stock compiler looks up types using RT.classForName
;; if the type is not a valuetype. why? does it make a difference?
(defn load-type [v]
  [(il/ldtoken v)
   (il/call (find-method Type "GetTypeFromHandle" RuntimeTypeHandle))])

(defn get-var [v]
  (if (.isDynamic v)
    (il/call (find-method Var "get"))
    (il/call (find-method Var "getRawRoot"))))

(defn load-regexp [r]
  [(il/ldstr (str r))
   (il/newobj (find-constructor System.Text.RegularExpressions.Regex String))])

(defn load-ratio [r]
  [(il/ldstr (pr-str r))
   (il/call (find-method clojure.lang.RT "readString" String))])

(defn load-bigint [r]
  [(il/ldstr (pr-str r))
   (il/call (find-method clojure.lang.RT "readString" String))])

;; multimethod?
(defn load-constant [k]
  (cond 
    (nil? k)                         (il/ldnull)
    (instance? System.String k)      (il/ldstr k)
    (instance? System.Boolean k)     (if k (il/ldc-i4-1) (il/ldc-i4-0))
    (instance? System.Int32 k)       (il/ldc-i4 k)
    (instance? System.Int64 k)       (il/ldc-i8 k)
    (instance? System.Single k)      (il/ldc-r4 k)
    (instance? System.Double k)      (il/ldc-r8 k)
    (instance? System.Char k)        [(il/ldc-i4-s (int k)) (il/conv-u2)]
    
    (instance? System.Type k)                           (load-type k)
    (instance? System.Text.RegularExpressions.Regex k)  (load-regexp k)
    (instance? clojure.lang.BigInt k)                   (load-bigint k)
    (instance? clojure.lang.Ratio k)                    (load-ratio k)
    (instance? clojure.lang.Symbol k)                   (load-symbol k)
    (instance? clojure.lang.Keyword k)                  (load-keyword k)
    (instance? clojure.lang.Var k)                      (load-var k)
    (instance? clojure.lang.PersistentList k)           (load-list k)
    (instance? clojure.lang.APersistentSet k)           (load-set k)
    (instance? clojure.lang.APersistentVector k)        (load-vector k)
    (instance? clojure.lang.APersistentMap k)           (load-map (seq k))))

(defn to-address [t]
  (let [l (il/local t)]
    [(il/stloc l)
     (il/ldloca l)]))

(defn cleanup-stack
  ([pcon]
   (if-not (nil? pcon)
     (if (.IsStatementContext pcon)
       (il/pop))))
  ([lasttype pcon]
   (if (and (some? pcon)
            (not= System.Void lasttype)
            (.IsStatementContext pcon))
     (il/pop))))

(def intrinsics
  {(find-method clojure.lang.RT "uncheckedIntCast" Double)
   (il/conv-i4)
   
   (find-method clojure.lang.RT "uncheckedIntCast" Int64)
   (il/conv-i4)
   
   (find-method clojure.lang.RT "uncheckedIntCast" Single)
   (il/conv-i4)
   
   (find-method clojure.lang.RT "longCast" Int64)
   []
   
   (find-method clojure.lang.RT "longCast" Int32)
   []
   
   (find-method clojure.lang.RT "uncheckedIntCast" Int32)
   []
   
   (find-method clojure.lang.Numbers "unchecked_add" Double Int64)
   [(il/conv-r8)
    (il/add)]
   
   ;; TODO replace Numbers.add with ovf intrinsics when possible? ~40% faster
   (find-method clojure.lang.Numbers "lt" Int64 Int64)
   (il/clt)
   
   (find-method clojure.lang.Numbers "lt" Double Double)
   (il/clt)
   
   (find-method clojure.lang.Numbers "lt" Double Int64)
   (il/clt)
   
   (find-method clojure.lang.Numbers "gt" Int64 Int64)
   (il/cgt)
   
   (find-method clojure.lang.Util "equiv" Int64 Int64)
   (il/ceq)
   
   (find-method clojure.lang.Util "equiv" Double Double)
   (il/ceq)
   
   (find-method clojure.lang.Util "equiv" Object Object)
   (il/ceq)
    
   (find-method clojure.lang.Numbers "inc" Int64)
   [(il/ldc-i8 1)
    (il/add-ovf)]
    
   (find-method clojure.lang.Numbers "unchecked_inc" Int64)
   [(il/ldc-i8 1)
    (il/add)]
   
   (find-method clojure.lang.Numbers "add" Int64 Int64)
   (il/add-ovf)
    
   (find-method clojure.lang.Numbers "add" Double Int64)
   [(il/conv-r8)
    (il/add-ovf)]
      
   (find-method clojure.lang.Numbers "add" Double Double)
   (il/add)
   
   (find-method clojure.lang.Numbers "unchecked_add" Double Double)
   (il/add)
   
   (find-method clojure.lang.Numbers "unchecked_add" Int64 Int64)
   (il/add)
   
   (find-method clojure.lang.Numbers "unchecked_multiply" Int64 Int64)
   (il/mul)
   
   (find-method clojure.lang.Numbers "unchecked_multiply" Double Double)
   (il/mul)
   
   (find-method clojure.lang.Numbers "unchecked_multiply" Double Int64)
   [(il/conv-r8)
    (il/mul)]
   
   (find-method clojure.lang.Numbers "unchecked_multiply" Int64 Double)
   [(let [loc (il/local Double)]
      [(il/stloc loc)
       (il/conv-r8)
       (il/ldloc loc)
       (il/mul)])]
   })

(defn has-arity-method [arities]
  (il/method
    "HasArity"
    (enum-or MethodAttributes/Public
             MethodAttributes/Virtual)
    Boolean [Int32]
    (let [ret-true (il/label)]
      [(map (fn [arity]
              [(il/ldarg-1)
               (load-constant arity)
               (il/beq ret-true)])
            arities)
       (il/ldc-i4-0)
       (il/ret)
       ret-true
       (il/ldc-i4-1)
       (il/ret)])))

;; ast -symbolize-> symbolics -emit-> bytecode
;;        M&GIC                M&GE

(def symbolize)

;; 42
;; "foo"
(defn literal-symbolizer
  [ast symbolizers]
  (let [data (data-map ast)]
    [(load-constant (data :Val))
     (cleanup-stack (data :ParsedContext))]))

(defn vector-symbolizer
  [ast symbolizers]
  (load-vector (-> ast data-map :_args)
               #(symbolize % symbolizers)))

(defn set-symbolizer
  [ast symbolizers]
  (load-set (-> ast data-map :_keys)
            #(symbolize % symbolizers)))

;; {:foo bar}
(defn map-symbolizer
  [ast symbolizers]
  (let [pcon (.ParsedContext ast)
        ks (take-nth 2 (.KeyVals ast))
        vs (take-nth 2 (drop 1 (.KeyVals ast)))]
    [(load-constant (int (+ (count ks) (count vs))))
     (il/newarr Object)
     (map (fn [i kv]
            [(il/dup)
             (load-constant (int i))
             (symbolize kv symbolizers)
             (convert kv Object)
             (il/stelem-ref)])
          (range)
          (interleave ks vs))
     (il/call (find-method RT "mapUniqueKeys" |System.Object[]|))
     (cleanup-stack pcon)]))

;; (f a b)
(defn invoke-symbolizer
  [ast symbolizers]
  (let [data (data-map ast)
        pcon (.ParsedContext ast)
        fexpr (:_fexpr data)
        args (:_args data)
        arity (count args)]
    [(symbolize fexpr symbolizers)
     (il/castclass IFn)
     (map (fn [a]
            [(symbolize a symbolizers)
             (convert a Object)])
          args)
     (il/callvirt (apply find-method IFn "invoke" (repeat arity Object)))
     (cleanup-stack pcon)]))

;; (new Foo)
(defn new-symbolizer
  [ast symbolizers]
  (let [{:keys [_args _type _ctor] :as data} (data-map ast)]
    (if _ctor
      ;; have constructor, normal newobj path 
      (let [arg-exprs (map #(.ArgExpr %) _args)
            ctor-param-types (->> _ctor .GetParameters (map #(.ParameterType %)))]
        ;; TODO what about LocalBindings?
        [(interleave
           (map #(symbolize % symbolizers)
                arg-exprs)
           (map #(convert %1 %2)
                arg-exprs
                ctor-param-types))
         (il/newobj _ctor)])
      ;; no constructor, might be initobj path
      (if (.IsValueType _type)
        (let [loc (il/local _type)]
          [(il/ldloca-s loc)
           (il/initobj _type)
           (il/ldloc loc)])
        (throw (Exception. (str "No constructor for non-valuetype " _type)))))))

(defn var-symbolizer
  [ast symbolizers]
  (let [pcon (.ParsedContext ast)
        v (.. ast Var)]
    [(load-var v)
     (get-var v)
     (cleanup-stack pcon)]))

(defn converted-args [args param-types symbolizers]
  (interleave
    (->> args
         (map :ArgExpr)
         (map #(symbolize % symbolizers)))
    (map #(convert %1 %2)
         (map :ArgExpr args)
         param-types)))

;; interop

;; (+ 1 2)
;; (Foo/Bar a b)
(defn static-method-symbolizer
  [ast symbolizers]
  (let [data (data-map ast)
        pcon (.ParsedContext ast)
        args (map data-map (:_args data))
        method (:_method data)
        method-parameter-types (->> method
                                    .GetParameters
                                    (map #(.ParameterType %)))]
    [(converted-args args method-parameter-types symbolizers)
     (if-let [intrinsic-bytecode (intrinsics method)]
       intrinsic-bytecode
       (il/call method))
     (cleanup-stack (.ReturnType method) pcon)]))

(defn instance-method-symbolizer
  [ast symbolizers]
  (let [data (data-map ast)
        pcon (.ParsedContext ast)
        target (:_target data)
        target-type (-> target .ClrType)
        args (map data-map (:_args data))
        method (:_method data)
        method-parameter-types (->> method
                                    .GetParameters
                                    (map #(.ParameterType %)))]
    
    [(symbolize target symbolizers)
     (if (.IsValueType target-type)
       (to-address target-type))
     (converted-args args method-parameter-types symbolizers) 
     (if (.IsValueType target-type)
       (il/call method)
       (il/callvirt method))
     (cleanup-stack (.ReturnType method)
                    pcon)]))

(defn static-property-symbolizer
  [ast symbolizers]
  (let [pcon (.ParsedContext ast)
        return-type (.ClrType ast)
        getter (-> ast data-map :_tinfo .GetGetMethod)]
    [(il/call getter)
     (cleanup-stack pcon)]))

(defn static-field-symbolizer
  [ast symbolizers]
  (let [{:keys [_tinfo] :as data} (data-map ast)
        pcon (.ParsedContext ast)
        return-type (clr-type ast)]
    [(if (.IsLiteral _tinfo)
       (load-constant (.GetRawConstantValue _tinfo))
       (il/ldsfld _tinfo) )
     (cleanup-stack pcon)]))

(defn instance-property-symbolizer
  [ast symbolizers]
  (let [data (data-map ast)
        pcon (.ParsedContext ast)
        return-type (.ClrType ast)
        target (:_target data)
        getter (-> data :_tinfo .GetGetMethod)]
    [(symbolize target symbolizers)
     (convert target Object)
     (il/callvirt getter)
     (cleanup-stack pcon)]))

(defn instance-field-symbolizer
  [ast symbolizers]
  (let [data (data-map ast)
        pcon (.ParsedContext ast)
        target (:_target data)
        field (:_tinfo data)
        return-type (.FieldType field)]
    [(symbolize target symbolizers)
     (il/ldfld field)
     (cleanup-stack pcon)]))

(defn fn-symbolizer
  [ast symbolizers]
  (let [{:keys [Name Constants Vars ProtocolCallsites VarCallsites Keywords _methods] :as data} (data-map ast)
        protected-static (enum-or FieldAttributes/Static FieldAttributes/FamORAssem)
        arities (->> _methods
                     (map data-map)
                     (map :NumParams))
        ;; set up var fields
        vars (keys Vars)
        var-fields (->> vars
                        (map #(il/field (type %)
                                        protected-static
                                        (gensym (str "var_" (.. % Symbol Name) "_"))))
                        (interleave vars)
                        (apply hash-map))
        var-symbolizer (fn fn-specialized-var-symbolizer
                         [this syms]
                         (let [pcon (.ParsedContext this)
                               v (or (-> this data-map :_var)
                                     (.. this Var))]
                           (if-let [cached-var (var-fields v)]
                             [(il/ldsfld cached-var)
                              (get-var v)
                              (cleanup-stack pcon)]
                             (symbolize this symbolizers))))
        
        ;; constants hack
        constant-revealing-symbolizers
        (assoc symbolizers
          LiteralExpr
          (fn fn-constant-revealing-symbolizer
            [this symbolizers]
            {::constant (.Val this)}))
        
        constants 
        (->> _methods
             (mapcat #(symbolize % constant-revealing-symbolizers))
             flatten
             (keep ::constant)
             (into #{}))
        
        constant-fields (->> constants
                             (map #(il/field (type %)
                                             protected-static
                                             (gensym (str "const_" (type %) "_"))))
                             (interleave constants)
                             (apply hash-map))
        constant-symbolizer (fn fn-specialized-constant-symbolizer
                              [this symbolizers]
                              (let [pcon (.ParsedContext this)
                                    k (.Val this)]
                                (if-let [cached-const (constant-fields k)]
                                  [(il/ldsfld cached-const)
                                   (cleanup-stack pcon)]
                                  (symbolize this symbolizers))))
        symbolizers (assoc symbolizers
                      VarExpr var-symbolizer
                      TheVarExpr var-symbolizer
                      LiteralExpr constant-symbolizer)]
    
    (mage.core/type
      Name
      TypeAttributes/Public []
      clojure.lang.AFn
      
      [(il/constructor
         MethodAttributes/Public
         CallingConventions/Standard []
         (il/ret))
       (il/constructor
         (enum-or MethodAttributes/Static)
         CallingConventions/Standard []
         [;; populate var fields
          (map (fn [[v fld]] [(load-var v) (il/stsfld fld)])
               var-fields)
          (map (fn [[k fld]] [(load-constant k) (il/stsfld fld)])
               constant-fields)
          (il/ret)])
       (has-arity-method arities)
       (map #(symbolize % symbolizers) _methods)])))

(defn fn-method-symbolizer
  [ast symbolizers]
  (let [{:keys [_retType _reqParms _body] :as data} (data-map ast)]
    (il/method "invoke"
               (enum-or MethodAttributes/Public
                        MethodAttributes/Virtual)
               _retType (mapv (constantly Object) _reqParms)
               [(symbolize _body symbolizers)
                (convert (.LastExpr _body) Object)
                (il/ret)]
               )))

(defn if-symbolizer
  [ast symbolizers]
  (let [{:keys [_testExpr _thenExpr _elseExpr] :as data} (data-map ast)
        pcon (.ParsedContext ast)
        false-label (il/label)
        end-label (il/label)]
    [(symbolize _testExpr symbolizers)
     (il/brfalse false-label)
     (symbolize _thenExpr symbolizers)
     (cleanup-stack (.ParsedContext _thenExpr))
     (il/br end-label)
     false-label
     (symbolize _elseExpr symbolizers)
     (cleanup-stack (.ParsedContext _elseExpr))
     end-label
     ]))

(defn body-symbolizer
  [ast symbolizers]
  [(map #(symbolize % symbolizers) (-> ast data-map :_exprs))
   (cleanup-stack (if-not (nil? ast) (.ParsedContext ast)))])

;; TODO is this nonsensical? throw an error?
(defn local-binding-symbolizer
  [ast symbolizers]
  (let [{:keys [IsArg Index ClrType]} (-> ast data-map :Binding data-map)]
    (if IsArg
      (load-argument Index)
      (il/ldloc (il/local (or ClrType Object))))))

(defn let-symbolizer
  [ast symbolizers]
  (let [{:keys [_bindingInits _body]} (-> ast data-map)
        pcon (.ParsedContext ast)
        bindings (map #(.Binding %) _bindingInits)
        binding-map (->> (interleave bindings
                                     (map #(il/local (clr-type (.Init %))) bindings))
                         (apply hash-map))
        recur-target (il/label)
        specialized-symbolizers
        (assoc symbolizers
          RecurExpr
          (fn let-recur-symbolizer
            [ast symbolizers]
            (let [{:keys [_args _loopLocals]} (data-map ast)]
              [(interleave
                 (map #(symbolize % symbolizers) _args)
                 (map #(if (.IsArg %)
                         (il/starg (.Index %)) ;; TODO is this starg right?
                         (il/stloc (binding-map %)))
                      _loopLocals))
               (il/br recur-target)]))
          LocalBindingExpr
          (fn let-body-symbolizer [ast syms]
            (if-let [loc (-> ast data-map :Binding binding-map)]
              (il/ldloc loc)
              (symbolize ast symbolizers))))]
    
    ;; emit local initializations
    [(map (fn [b loc]
            [(symbolize (.Init b) specialized-symbolizers)
             (il/stloc loc)])
          bindings
          (map binding-map bindings))
    
    ;; mark recur target
     recur-target
     
     ;; emit body with specialized symbolizers
     (symbolize _body specialized-symbolizers)
     (cleanup-stack pcon)]))

(defn monitor-enter-symbolizer
  [ast symbolizers]
  (let [{:keys [_target]} (data-map ast)]
    [(symbolize _target symbolizers)
     (il/call (find-method Monitor "Enter" Object))
     (il/ldnull)]))

(defn monitor-exit-symbolizer
  [ast symbolizers]
  (let [{:keys [_target]} (data-map ast)]
    [(symbolize _target symbolizers)
     (il/call (find-method Monitor "Exit" Object))
     (il/ldnull)]))

(defn instance-of-symbolizer
  [ast symbolizers]
  (let [{:keys [_expr _t]} (data-map ast)]
    [(symbolize _expr symbolizers)
     (convert _expr Object)
     (il/isinst _t)
     (il/ldnull)
     (il/cgt-un)]))

(defn throw-symbolizer
  [ast symbolizers]
  (let [{:keys [_excExpr]} (data-map ast)]
    [(symbolize _excExpr symbolizers)
     (convert _excExpr Exception)
     (il/throw)]))

(defn try-symbolizer
  [ast symbolizers]
  (let [{:keys [_tryExpr _finallyExpr _catchExprs]} (data-map ast)
        expr-type (clr-type ast)
        ret (il/local expr-type)
        catch-symbolizer
        (fn catch-symbolizer
          [ast symbolizers]
          (let [{:keys [_type _lb _handler]} (data-map ast)
                exception-local (il/local _type)
                catch-binding-symbolizers
                (assoc symbolizers LocalBindingExpr
                  (fn catch-body-symbolizer [ast syms]
                    (if (= _lb (-> ast data-map :Binding))
                      (il/ldloc exception-local)
                      (symbolize ast syms))))]
            (il/catch _type
                      [(il/stloc exception-local)
                       (symbolize _handler catch-binding-symbolizers)
                       (convert _handler expr-type)
                       (il/stloc ret)])))
        catch-symbolizers (assoc symbolizers
                            TryExpr+CatchClause
                            catch-symbolizer)]
    (if (and (empty? _catchExprs)
             (nil? _finallyExpr))
      (symbolize _tryExpr symbolizers)
      [(il/exception
         [(symbolize _tryExpr symbolizers)
          (convert _tryExpr expr-type)
          (il/stloc ret)
          (map #(catch-symbolizer % catch-symbolizers) _catchExprs)
          (if _finallyExpr
            (il/finally (symbolize _finallyExpr symbolizers)))])
       (il/ldloc ret)])))

(defn unresolved-var-symbolizer
  [ast symbolizers]
  nil)

(defn empty-symbolizer
  [ast symbolizers]
  (let [{:keys [_coll]} (data-map ast)]
    (cond
      (instance? IPersistentList _coll)   (il/ldsfld (.GetField PersistentList "EMPTY"))
      (instance? LazySeq _coll)           (il/ldsfld (.GetField PersistentList "EMPTY"))
      (instance? IPersistentVector _coll) (il/ldsfld (.GetField PersistentVector "EMPTY"))
      (instance? IPersistentMap _coll)    (il/ldsfld (.GetField PersistentArrayMap "EMPTY"))
      (instance? IPersistentSet _coll)    (il/ldsfld (.GetField PersistentHashSet "EMPTY"))
      :else                               (throw (InvalidOperationException. "Unknown collection type.")))))

(defn import-symbolizer
  [ast symbolizers]
  (let [cls (-> ast data-map :_c)]
    [(il/call (property-getter clojure.lang.Compiler "CurrentNamespace"))
     (load-constant cls)
     (il/call (find-method clojure.lang.RT "classForName" String))
     (il/call (find-method clojure.lang.Namespace "importClass" Type))]))

(defn keyword-invoke-symbolizer
  [ast symbolizers]
  (let [{:keys [_kw _target]} (data-map ast)
        pcon (.ParsedContext ast)]
    [(symbolize _kw symbolizers)
    (il/castclass IFn)
    (symbolize _target symbolizers)
    (convert _target Object)
    (il/callvirt (find-method IFn "invoke" Object))
    (cleanup-stack pcon)]))

(def base-symbolizers
  {LiteralExpr          literal-symbolizer
   VectorExpr           vector-symbolizer
   MapExpr              map-symbolizer
   SetExpr              set-symbolizer
   InvokeExpr           invoke-symbolizer
   NewExpr              new-symbolizer
   VarExpr              var-symbolizer
   TheVarExpr           var-symbolizer
   StaticMethodExpr     static-method-symbolizer
   InstanceMethodExpr   instance-method-symbolizer
   StaticPropertyExpr   static-property-symbolizer
   StaticFieldExpr      static-field-symbolizer
   InstancePropertyExpr instance-property-symbolizer
   InstanceFieldExpr    instance-field-symbolizer
   FnExpr               fn-symbolizer
   FnMethod             fn-method-symbolizer
   IfExpr               if-symbolizer
   BodyExpr             body-symbolizer
   LocalBindingExpr     local-binding-symbolizer 
   LetExpr              let-symbolizer
   ; CaseExpr             case-symbolizer
   MonitorEnterExpr     monitor-enter-symbolizer
   MonitorExitExpr      monitor-exit-symbolizer
   InstanceOfExpr       instance-of-symbolizer
   ThrowExpr            throw-symbolizer
   TryExpr              try-symbolizer
   UnresolvedVarExpr    unresolved-var-symbolizer
   EmptyExpr            empty-symbolizer
   ImportExpr           import-symbolizer
   KeywordInvokeExpr    keyword-invoke-symbolizer
   })

(defn ast->symbolizer [ast symbolizers]
  (or (->> ast type symbolizers)
      (->> ast
           type
           bases
           (map symbolizers)
           (remove nil?)
           first)
      (throw (Exception. (str "No symbolizer for " ast)))))

(defn symbolize [ast symbolizers]
  (if-let [symbolizer (ast->symbolizer ast symbolizers)]
    (symbolizer ast symbolizers)))

(comment
  (defn case-shift-mask [shift mask]
    (if-not (zero? mask)
      [(load-constant shift)
       (il/shr)
       (load-constant mask)
       (il/and)]))
  
  (defn case-int-expr [ast default-label symbolizers]
    (let [{:keys [_expr _shift _mask]} (data-map ast)]
      [(symbolize expr symbolizers)
       (il/call (find-method clojure.lang.Util "IsNonCharNumeric" Object))
       (il/brfalse default-label)
       (symbolize expr symbolizers)
       (il/call (find-method clojure.lang.Util "ConvertToInt" Object))
       (case-shift-mask _shift _mask)
       ])
    
    )
  
  (defn case-symbolizer
    [ast symbolizers]
    (let [{:keys [_expr _shift _mask _low _high
                  _defaultExpr _tests _thens
                  _switchType _testType _skipCheck]} (data-map ast)]
      (if (= _testType :int)
        case-int-expr
        case-hash-expr
        )
      ; (map (fn [a] (il/label)) (.Keys _tests))
      (apply str (.Values _tests))
      ; (str (count _tests))
      )
    ))