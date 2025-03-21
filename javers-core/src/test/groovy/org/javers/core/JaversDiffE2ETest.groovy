package org.javers.core

import groovy.json.JsonSlurper
import org.javers.core.diff.AbstractDiffTest
import org.javers.core.diff.DiffAssert
import org.javers.core.diff.ListCompareAlgorithm
import org.javers.core.diff.changetype.*
import org.javers.core.diff.changetype.container.ListChange
import org.javers.core.diff.changetype.container.ValueAdded
import org.javers.core.diff.changetype.map.EntryAdded
import org.javers.core.diff.changetype.map.MapChange
import org.javers.core.examples.model.Person
import org.javers.core.json.DummyPointJsonTypeAdapter
import org.javers.core.json.DummyPointNativeTypeAdapter
import org.javers.core.metamodel.annotation.DiffIgnoreProperties
import org.javers.core.metamodel.annotation.DiffInclude
import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.ShallowReference
import org.javers.core.metamodel.annotation.TypeName
import org.javers.core.metamodel.clazz.EntityDefinition
import org.javers.core.metamodel.clazz.EntityDefinitionBuilder
import org.javers.core.metamodel.property.Property
import org.javers.core.metamodel.type.IgnoredType
import org.javers.core.metamodel.type.ManagedType
import org.javers.core.metamodel.type.SetType
import org.javers.core.metamodel.type.ValueObjectType
import org.javers.core.model.*
import spock.lang.Unroll

import jakarta.persistence.EmbeddedId

import static GlobalIdTestBuilder.instanceId
import static org.javers.core.JaversBuilder.javers
import static org.javers.core.JaversTestBuilder.javersTestAssembly
import static org.javers.core.MappingStyle.BEAN
import static org.javers.core.MappingStyle.FIELD
import static org.javers.core.metamodel.clazz.EntityDefinitionBuilder.entityDefinition
import static org.javers.core.metamodel.clazz.ValueObjectDefinitionBuilder.valueObjectDefinition
import static org.javers.core.model.DummyUser.Sex.FEMALE
import static org.javers.core.model.DummyUser.Sex.MALE
import static org.javers.core.model.DummyUser.dummyUser
import static org.javers.core.model.DummyUserWithPoint.userWithPoint

/**
 * @author bartosz walacik
 */
class JaversDiffE2ETest extends AbstractDiffTest {

    class PropsClass {
        @DiffInclude int id
        int a
        int b
    }

    def "should allow passing null to currentVersion"(){
      given:
      def javers = JaversBuilder.javers().build()

      when:
      def diff = javers.compare(new SnapshotEntity(id:1), null)

      then:
      diff.changes.size() == 1
      diff.changes.first() instanceof ObjectRemoved
    }

    def "should allow passing null to oldVersion"(){
        given:
        def javers = JaversBuilder.javers().build()

        when:
        def diff = javers.compare(null, new SnapshotEntity(id:1))

        then:
        diff.changes.size() == 1
        diff.changes.first() instanceof NewObject
    }

    def "should allow passing two nulls to compare()"(){
        given:
        def javers = JaversBuilder.javers().build()

        when:
        def diff = javers.compare(null, null)

        then:
        diff.changes.size() == 0
    }

    @Unroll
    def "should ignore all props of #classType which are not in the 'included' list of properties"(){
      given:
      def javers = JaversBuilder.javers().registerType(definition).build()

      when:
      def left =  new PropsClass(id:1, a:2, b:3)
      def right = new PropsClass(id:1, a:4, b:6)
      def diff = javers.compare(left, right)

      then:
      !diff.changes.size()

      where:
      definition << [entityDefinition(PropsClass)
                             .withIdPropertyName("id").build(),
                     valueObjectDefinition(PropsClass)
                             .withIncludedProperties(["id"]).build()
      ]
      classType << ["EntityType", "ValueObjectType"]
    }

    def "should extract Property from PropertyChange"(){
      given:
      def javers = JaversTestBuilder.newInstance()

      when:
      def diff = javers.compare(new Person('1','bob'), new Person('1','bobby'))
      PropertyChange propertyChange = diff.changes[0]

      Property property = javers.getProperty( propertyChange )

      then:
      property.name == 'name'
      !property.looksLikeId()
    }

    def "should use reflectiveToString() to build InstanceId"(){
        given:
        def javers = JaversTestBuilder.newInstance()
        def left  = new DummyEntityWithEmbeddedId(point: new DummyPoint(1,2), someVal: 5)
        def right = new DummyEntityWithEmbeddedId(point: new DummyPoint(1,2), someVal: 6)

        when:
        def diff = javers.compare(left,right)

        then:
        DiffAssert.assertThat(diff).hasChanges(1).hasValueChangeAt("someVal",5,6)

        diff.changes[0].affectedGlobalId.value().endsWith("DummyEntityWithEmbeddedId/1,2")
    }

    class DummyCompositePoint {
        @EmbeddedId DummyPoint dummyPoint
        int value
    }

    def "should use custom toString function when provided for building InstanceId"(){
        given:
        def javers = JaversBuilder.javers()
                .registerValue(DummyPoint, {a,b -> Objects.equals(a,b)}, {x -> x.getStringId()})
                .build()
        def left  = new DummyCompositePoint(dummyPoint: new DummyPoint(1,2), value:5)
        def right = new DummyCompositePoint(dummyPoint: new DummyPoint(1,2), value:6)

        when:
        def diff = javers.compare(left,right)

        then:
        DiffAssert.assertThat(diff).hasChanges(1).hasValueChangeAt("value",5,6)
        diff.changes.get(0).affectedGlobalId.value() == DummyCompositePoint.class.name+"/(1,2)"
    }

    def "should create NewObject for all nodes in initial diff"() {
        given:
        def javers = JaversTestBuilder.newInstance()
        DummyUser left = dummyUser().withDetails()

        when:
        def diff = javers.initial(left)

        then:
        DiffAssert.assertThat(diff).has(2, NewObject)
    }

    def "should create properties snapshot of NewObject by default"() {
        given:
        def javers = JaversBuilder.javers().build()
        def left =  new DummyUser(name: "kazik")
        def right = new DummyUser(name: "kazik", dummyUserDetails: new DummyUserDetails(id: 1, someValue: "some"))

        when:
        def diff = javers.compare(left, right)

        then:
        DiffAssert.assertThat(diff).hasChanges(4)
                  .hasNewObject(instanceId(1,DummyUserDetails))
                  .hasValueChangeAt("id", null, 1)
                  .hasValueChangeAt("someValue", null, "some")
                  .hasReferenceChangeAt("dummyUserDetails",null,instanceId(1,DummyUserDetails))
    }

    def "should not create properties snapshot of NewObject when disabled"() {
        given:
        def javers = JaversBuilder.javers().withInitialChanges(false).build()
        def left =  new DummyUser(name: "kazik")
        def right = new DummyUser(name: "kazik", dummyUserDetails: new DummyUserDetails(id: 1, someValue: "some"))

        when:
        def diff = javers.compare(left, right)

        then:
        DiffAssert.assertThat(diff).hasChanges(2)
                .hasNewObject(instanceId(1,DummyUserDetails))
                .hasReferenceChangeAt("dummyUserDetails",null,instanceId(1,DummyUserDetails))
    }

    def "should create valueChange with Enum" () {
        given:
        def user =  dummyUser().withSex(FEMALE)
        def user2 = dummyUser().withSex(MALE)
        def javers = JaversTestBuilder.newInstance()

        when:
        def diff = javers.compare(user, user2)

        then:
        diff.changes.size() == 1
        def change = diff.changes[0]
        change.left == FEMALE
        change.right == MALE
    }

    def "should serialize whole Diff"() {
        given:
        def user =  dummyUser().withSex(FEMALE)
        def user2 = dummyUser().withSex(MALE).withDetails()
        def javers = JaversTestBuilder.newInstance()

        when:
        def diff = javers.compare(user, user2)
        def jsonText = javers.getJsonConverter().toJson(diff)

        then:
        def json = new JsonSlurper().parseText(jsonText)
        json.changes.size() == 4
        json.changes[0].changeType == "NewObject"
    }

    def "should support custom JsonTypeAdapter for ValueChange"() {
        given:
        def javers = javers().registerValueTypeAdapter( new DummyPointJsonTypeAdapter() )
                             .build()

        when:
        def diff = javers.compare(userWithPoint(1,2), userWithPoint(1,3))
        def jsonText = javers.getJsonConverter().toJson(diff)

        then:
        def json = new JsonSlurper().parseText(jsonText)
        def change = json.changes[0];
        change.globalId.valueObject == "org.javers.core.model.DummyUserWithPoint"
        change.changeType == "ValueChange"
        change.property == "point"
        change.left == "1,2" //this is most important in this test
        change.right == "1,3" //this is most important in this test
    }

    def "should support custom native Gson TypeAdapter"() {
        given:
        def javers = javers()
                .registerValueGsonTypeAdapter(DummyPoint, new DummyPointNativeTypeAdapter() )
                .build()

        when:
        def diff = javers.compare(userWithPoint(1,2), userWithPoint(1,3))
        def jsonText = javers.getJsonConverter().toJson(diff)
        //println("jsonText:\n"+jsonText)

        then:
        def json = new JsonSlurper().parseText(jsonText)
        json.changes[0].left == "1,2"
        json.changes[0].right == "1,3"
    }

    def "should understand primitive default values when creating NewObject snapshot"() {
        given:
        def javers = javers().build()

        when:
        def diff = javers.initial(new PrimitiveEntity())

        then:
        DiffAssert.assertThat(diff)
                .hasNewObject(instanceId("a",PrimitiveEntity))
                .hasValueChangeAt("id", null, "a")
    }

    def "should understand primitive default values when creating ValueChange"() {
        given:
        def javers = javers().build()

        when:
        def diff = javers.compare(new PrimitiveEntity(), new PrimitiveEntity())

        then:
        DiffAssert.assertThat(diff).hasChanges(0)
    }

    def "should serialize the Diff object"() {
        given:
        def javers = javers().build()
        def user =  new DummyUser(name:"id", sex: MALE,   age: 5, stringSet: ["a"])
        def user2 = new DummyUser(name:"id", sex: FEMALE, age: 6, stringSet: ["b"])
        def tmpFile = File.createTempFile("serializedDiff", ".ser")

        when:
        def diff = javers.compare(user, user2)

        //serialize diff
        new ObjectOutputStream(new FileOutputStream(tmpFile.path)).writeObject(diff)

        //deserialize diff
        def deserializedDiff = new ObjectInputStream(new FileInputStream(tmpFile.path)).readObject()

        then:
        List changes = deserializedDiff.changes
        changes.size() == 3

        def ageChange = changes.find {it.propertyName == "age"}
        ageChange.left == 5
        ageChange.right == 6
        ageChange.affectedGlobalId.cdoId == "id"
        ageChange.affectedGlobalId.typeName == "org.javers.core.model.DummyUser"

        changes.count{ it.propertyName == "age" } // == 1

        changes.count{ it.propertyName == "stringSet" } // == 1
    }

    def "should compare ShallowReferences using regular ReferenceChange"() {
        given:
        def javers = javers().build()
        def left =  new SnapshotEntity(id:1, shallowPhone: new ShallowPhone(1))
        def right = new SnapshotEntity(id:1, shallowPhone: new ShallowPhone(2))

        when:
        ReferenceChange change = javers.compare(left, right).changes.find{it instanceof  ReferenceChange}

        then:
        change.left.value() == ShallowPhone.name+"/1"
        change.right.value() == ShallowPhone.name+"/2"
    }

    def "should not compare properties when class is mapped as ShallowReference"() {
        given:
        def javers = javers().build()
        def left =  new SnapshotEntity(id:1, shallowPhone: new ShallowPhone(1, "123", new CategoryC(1)))
        def right = new SnapshotEntity(id:1, shallowPhone: new ShallowPhone(1, "321", new CategoryC(2)))

        expect:
        javers.compare(left, right).hasChanges() == false
    }

    @Unroll
    def "should use ReferenceChange when #propType is annotated as ShallowReferences"() {
        given:
        def javers = javers().withMappingStyle(mapping).build()
        def left =  new PhoneWithShallowCategory(id:1, shallowCategory:new CategoryC(1, "old shallow"))
        def right = new PhoneWithShallowCategory(id:1, shallowCategory:new CategoryC(2, "new shallow"))

        when:
        def changes = javers.compare(left, right).getChangesByType(ReferenceChange)

        then:
        changes.size() == 1
        changes[0] instanceof ReferenceChange
        changes[0].left.value() == CategoryC.name+"/1"
        changes[0].right.value() == CategoryC.name+"/2"

        where:
        propType << ["field", "getter"]
        mapping <<  [FIELD, BEAN]
    }

    @Unroll
    def "should not compare properties when #propType is annotated as ShallowReference"() {
        given:
        def javers = javers().withMappingStyle(mapping).build()
        def left =  new PhoneWithShallowCategory(id:1, shallowCategory:new CategoryC(1, "old shallow"), deepCategory:new CategoryC(2, "old deep"))
        def right = new PhoneWithShallowCategory(id:1, shallowCategory:new CategoryC(1, "new shallow"), deepCategory:new CategoryC(2, "new deep"))

        when:
        def changes = javers.compare(left, right).changes

        then:
        changes.size() == 1
        changes[0] instanceof ValueChange
        changes[0].left == "old deep"
        changes[0].right == "new deep"

        where:
        propType << ["field", "getter"]
        mapping <<  [FIELD, BEAN]
    }

    def "should ignore properties with @DiffIgnore or @Transient"(){
        given:
        def javers = javers().build()
        def left =  new DummyUser(name:'name', propertyWithTransientAnn:1, propertyWithDiffIgnoreAnn:1)
        def right = new DummyUser(name:'name', propertyWithTransientAnn:2, propertyWithDiffIgnoreAnn:2)

        expect:
        javers.compare(left, right).changes.size() == 0
    }

    def "should ignore properties with @DiffIgnored type"() {
        given:
        def javers = javers().build()
        def left =  new DummyUser(name: "a",
                                  propertyWithDiffIgnoredType: new DummyIgnoredType(value: 1),
                                  propertyWithDiffIgnoredSubtype: new IgnoredSubType(value: 1))
        def right = new DummyUser(name: "a",
                                  propertyWithDiffIgnoredType: new DummyIgnoredType(value: 2),
                                  propertyWithDiffIgnoredSubtype: new IgnoredSubType(value: 2))

        when:
        def diff = javers.compare(left, right)

        then:
        diff.changes.size() == 0
        javers.getTypeMapping(DummyIgnoredType) instanceof IgnoredType
        javers.getTypeMapping(IgnoredSubType) instanceof IgnoredType
    }

    @DiffIgnoreProperties(["field1", "field2", "getField1", "getField2"])
    class ClassWithDiffIgnoreProperties {
        @Id int name

        int field1
        int field2

        int getField1() {
            return field1
        }
        int getField2() {
            return field2
        }
        int getName() {
            return name
        }
    }

    @Unroll
    def "should ignore properties listed in @DiffIgnoreProperties in mappingStyle #mappingStyle"(){
        given:
        def javers = javers().withMappingStyle(mappingStyle).build()

        def left =  new ClassWithDiffIgnoreProperties("name":1, field1: 1, field2: 1)
        def right = new ClassWithDiffIgnoreProperties("name":1, field1: 2, field2: 2)

        when:
        def diff = javers.compare(left, right)

        then:
        diff.changes.size() == 0

        where:
        mappingStyle << [ MappingStyle.FIELD, MappingStyle.BEAN]
    }

    class Foo {
        Bar bar
        BarBar barBar
    }

    class Bar {
        int value
    }
    class BarBar {
        int value
    }

    def "should ignore properties with type explicitly registered as ignored" () {
        given:
        def javers = javers().registerIgnoredClass(Bar).build()
        def left =  new Foo(bar:new Bar(value:1))
        def right = new Foo(bar:new Bar(value:2))

        when:
        def diff = javers.compare(left, right)

        then:
        diff.changes.size() == 0
        javers.getTypeMapping(Bar) instanceof IgnoredType
    }

    def "should ignore properties with type registered as ignored using IgnoredClassesStrategy" () {
        given:
        def javers = javers().registerIgnoredClassesStrategy(
                {c -> return c == Bar}
        ).build()
        def left =  new Foo(bar:new Bar(value:1), barBar: new BarBar(value:1))
        def right = new Foo(bar:new Bar(value:2), barBar: new BarBar(value:2))

        when:
        def diff = javers.compare(left, right)

        then:
        diff.changes.size() == 1
        println diff.changes[0].propertyNameWithPath == 'barBar.value'

        javers.getTypeMapping(Bar) instanceof IgnoredType
        javers.getTypeMapping(BarBar) instanceof ValueObjectType
    }

    def "should ignore properties declared in a class with @IgnoreDeclaredProperties"(){
        given:
        def javers = javers().build()
        def left =  new DummyIgnoredPropertiesType(name:"bob", age: 15, propertyThatShouldBeIgnored: 1, anotherIgnored: 1)
        def right = new DummyIgnoredPropertiesType(name:"bob", age: 16, propertyThatShouldBeIgnored: 2, anotherIgnored: 2)

        when:
        def diff = javers.compare(left, right)

        then:
        diff.changes.size() == 1
        diff.changes[0].propertyName == "age"
    }

    def "should compare ValueObjects in Lists as Sets when ListCompareAlgorithm.SET is enabled"() {
      given:
      def javers = javers().withListCompareAlgorithm(ListCompareAlgorithm.AS_SET).build()

      def s1 = new SnapshotEntity(id: 1, listOfValueObjects: [
                  new DummyAddress("London", "some"),
                  new DummyAddress("Paris",  "some")
          ])

      def s2 = new SnapshotEntity(id: 1, listOfValueObjects: [
              new DummyAddress("Paris",  "some"),
              new DummyAddress("Warsaw", "some"),
              new DummyAddress("London", "some")
          ])

       when:
       def diff = javers.compare(s1, s2)
       println diff.prettyPrint()

       then:
       diff.changes.size() == 3

       diff.getChangesByType(ListChange).size() == 1

       def lChange = diff.getChangesByType(ListChange)[0]
       lChange.changes[0] instanceof ValueAdded

       def addedId = lChange.changes[0].addedValue.value()
       def expectedAddedId = SnapshotEntity.class.name + "/1#listOfValueObjects/"+
               javersTestAssembly().hash(new DummyAddress("Warsaw", "some"))

       addedId == expectedAddedId
    }

    def "should compare Values in Lists as Sets when ListCompareAlgorithm.SET is enabled"() {
      given:
      def javers = javers().withListCompareAlgorithm(ListCompareAlgorithm.AS_SET).build()
      def left =  new DummyUser(name:"bob", stringList: ['z', 'a', 'b'])
      def right = new DummyUser(name:"bob", stringList: ['cc', 'b', 'z', 'a'])

      when:
      def diff = javers.compare(left, right)

      then:
      diff.changes.size() == 1
      def change = diff.changes[0]
      change instanceof ListChange
      change.changes.size() == 1
      change.changes[0] instanceof ValueAdded
      change.changes[0].addedValue == 'cc'
    }

    class SetValueObject {
        String some
        SnapshotEntity ref
    }

    class ValueObjectHolder {
        @Id int id
        Set<SetValueObject> valueObjects
    }

    class ValueObjectHolderAnnotated {
        @Id int id
        @ShallowReference Set<SetValueObject> valueObjects
    }

    def "should follow and deeply compare entities referenced from ValueObjects inside Set"(){
      given:
      def javers = javers().build()
      def left = new ValueObjectHolder(id:1, valueObjects:
                [new SetValueObject(some:'a'),
                 new SetValueObject(some:'b', ref: new SnapshotEntity(id:1, intProperty:5))
                ])
      def right= new ValueObjectHolder(id:1, valueObjects:
                [new SetValueObject(some:'b', ref: new SnapshotEntity(id:1, intProperty:6)),
                 new SetValueObject(some:'a')
                ])

      when:
      def changes = javers.compare(left, right).changes

      then:
      changes.size() == 1
      changes[0].affectedGlobalId.value() == SnapshotEntity.getName()+"/1"
      changes[0].left == 5
      changes[0].right == 6
    }

    def "should map Set field to SetType when entity is not registered"(){
        given:
        def javers = javers().build()

        when:
        ManagedType mapping = javers.getTypeMapping(ValueObjectHolder.class)

        then:
        mapping.getProperty('valueObjects').getType() instanceof SetType
    }

    def "should map Set field to SetType when entity is registered"(){
        given:
        def javers = javers()
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(ValueObjectHolder.class)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjects'])
                        .build())
                .build()

        when:
        ManagedType mapping = javers.getTypeMapping(ValueObjectHolder.class)

        then:
        mapping.getProperty('valueObjects').getType() instanceof SetType
    }

    def "should map Set field to SetType when entity is registered and has @ShallowReference"(){
        given:
        def javers = javers()
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(ValueObjectHolderAnnotated)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjects'])
                        .build())
                .build()

        when:
        ManagedType mapping = javers.getTypeMapping(ValueObjectHolderAnnotated.class)

        then:
        mapping.getProperty('valueObjects').getType() instanceof SetType
    }

    def "should map Set field to SetType when entity is registered with shallow properties"(){
        given:
        def javers = javers()
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(ValueObjectHolder.class)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjects'])
                        .withShallowProperties(['valueObjects'])
                        .build())
                .build()

        when:
        ManagedType mapping = javers.getTypeMapping(ValueObjectHolder.class)

        then:
        mapping.getProperty('valueObjects').getType() instanceof SetType
    }

    class EntityHolder {
        @Id int id
        ValueObjectHolder valueObjectHolder
    }

    def "should use entity definition for entity that is not a shallow reference"(){
        given:
        def javers = javers()
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(EntityHolder.class)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjectHolder'])
                        .withTypeName(EntityHolder.simpleName)
                        .build())
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(ValueObjectHolder.class)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjects'])
                        .withShallowProperties(['valueObjects'])
                        .withTypeName(ValueObjectHolder.simpleName)
                        .build())
                .build()

        when:
        ManagedType ehMapping = javers.getTypeMapping(EntityHolder.class)
        ManagedType vohMapping = javers.getTypeMapping(ValueObjectHolder.class)

        then:
        ehMapping.name == EntityHolder.simpleName
        ehMapping.properties.findAll { it.shallowReference }.isEmpty()
        vohMapping.name == ValueObjectHolder.simpleName
        vohMapping.properties.find { it.shallowReference }?.name == 'valueObjects'
    }

    def "should use entity definition for entity that is a shallow reference"(){
        given:
        def javers = javers()
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(EntityHolder.class)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjectHolder'])
                        .withShallowProperties(['valueObjectHolder'])
                        .withTypeName(EntityHolder.simpleName)
                        .build())
                .registerEntity(EntityDefinitionBuilder
                        .entityDefinition(ValueObjectHolder.class)
                        .withIdPropertyName('id')
                        .withIncludedProperties(['id', 'valueObjects'])
                        .withShallowProperties(['valueObjects'])
                        .withTypeName(ValueObjectHolder.simpleName)
                        .build())
                .build()

        when:
        ManagedType ehMapping = javers.getTypeMapping(EntityHolder.class)
        ManagedType vohMapping = javers.getTypeMapping(ValueObjectHolder.class)

        then:
        ehMapping.name == EntityHolder.simpleName
        ehMapping.properties.find { it.shallowReference }?.name == 'valueObjectHolder'
        vohMapping.name == ValueObjectHolder.simpleName
        vohMapping.properties.find { it.shallowReference }?.name == 'valueObjects'
    }

    @TypeName("ClassWithValue")
    static class Class1WithValue {
        @Id int id
        String sharedValue
        String firstProperty
    }

    @TypeName("ClassWithValue")
    static class Class2WithValue {
        @Id int id
        String sharedValue
        String secondProperty
    }

    def "should report which value properties were added, removed or updated"() {
        given:
        def javers = javers().build()
        def object1 = new Class1WithValue(id:1, sharedValue: "Some Name",     firstProperty:  "one")
        def object2 = new Class2WithValue(id:1, sharedValue: "Some New Name", secondProperty: "two")

        when:
        def diff = javers.compare(object1, object2)

        then:
        diff.changes.size() == 3

        def vChange = diff.changes.find{it.propertyValueChanged}
        vChange.propertyName == "sharedValue"
        vChange.left == "Some Name"
        vChange.right == "Some New Name"

        def aChange = diff.changes.find{it.propertyAdded}
        aChange.propertyName == "secondProperty"
        aChange.left == null
        aChange.right == "two"

        def rChange = diff.changes.find{it.propertyRemoved}
        rChange.propertyName == "firstProperty"
        rChange.left == "one"
        rChange.right == null
    }

    @TypeName("ClassWithRef")
    class Class1WithRef {
        @Id int id
        SnapshotEntity sharedRef
        SnapshotEntity firstRef
    }

    @TypeName("ClassWithRef")
    class Class2WithRef {
        @Id int id
        SnapshotEntity sharedRef
        SnapshotEntity secondRef
    }

    def "should report when a reference property is added, removed or updated"() {
        given:
        def javers = javers().build()
        def object1 = new Class1WithRef(id:1, sharedRef:new SnapshotEntity(id:1), firstRef:  new SnapshotEntity(id:21))
        def object2 = new Class2WithRef(id:1, sharedRef:new SnapshotEntity(id:2), secondRef: new SnapshotEntity(id:22))

        when:
        def diff = javers.compare(object1, object2)
        def changes = diff.getChangesByType(PropertyChange)

        then:
        changes.size() == 3

        def vChange = changes.find{it.propertyValueChanged}
        vChange.propertyName == "sharedRef"
        vChange.left.value().endsWith "SnapshotEntity/1"
        vChange.right.value().endsWith "SnapshotEntity/2"

        def aChange = changes.find{it.propertyAdded}
        aChange.propertyName == "secondRef"
        aChange.left == null
        aChange.right.value().endsWith "SnapshotEntity/22"

        def rChange = changes.find{it.propertyRemoved}
        rChange.propertyName == "firstRef"
        rChange.left.value().endsWith "SnapshotEntity/21"
        rChange.right == null
    }

    @TypeName("E")
    class Entity1 {
        @Id int id
    }

    @TypeName("E")
    class Entity2 {
        @Id int id
        List<String> propsList
        Set<String> propsSet
        Map<String, String> propsMap
    }

    def "should report when a list property is added or removed"(){
      given:
      def javers = javers().build()
      def object1 = new Entity1(id:1)
      def object2 = new Entity2(id:1, propsList: ["p"], propsSet: ["p"] as Set, propsMap: ["k": "v"])

      when:
      def diff = javers.compare(object1, object2)
      def changes = diff.getChangesByType(PropertyChange)

      then:
      changes.size() == 3
      changes[0].propertyAdded
      changes[1].propertyAdded
      changes[2].propertyAdded

      when:
      diff = javers.compare(object2, object1)
      changes = diff.getChangesByType(PropertyChange)

      then:
      changes.size() == 3
      changes[0].propertyRemoved
      changes[1].propertyRemoved
      changes[2].propertyRemoved
    }

    class TestChild {
        String value
    }

    class TestParent {
        Map<String, TestChild> childMap = new HashMap<>();
    }

    def "Map entry with null value should be treated as no entry while comparing"() {
        given:
        def javers = javers().withInitialChanges(false).build()
        def testParent1 = new TestParent(childMap: ["k": null])
        def testParent2 = new TestParent(childMap: ["k": new TestChild(value: "v")])

        when:
        def diff = javers.compare(testParent1, testParent2)
        def changes = diff.getChangesByType(PropertyChange)

        then:
        changes.size() == 1
        changes[0] instanceof MapChange
        changes[0].entryChanges[0] instanceof EntryAdded

        when:
        testParent1 = new TestParent(childMap: ["k": null])
        testParent2 = new TestParent(childMap: [:])
        diff = javers.compare(testParent1, testParent2)
        changes = diff.getChangesByType(PropertyChange)

        then:
        changes.size() == 0
    }

    class Fee {
        String feeCode;
        String amount;
    }

    class Parent {
        List<Fee> feeList;
        Map<String, List<Fee>> userFeeList;
    }

    def "Entity registration breaks collection comparison"() {
        given:
        def javers = javers().registerEntity(new EntityDefinition(Fee.class, "feeCode"))
                .withListCompareAlgorithm(ListCompareAlgorithm.AS_SET).build()
        def feeList1 = new ArrayList();
        feeList1.addAll([new Fee(feeCode: "comm", amount: "100"), new Fee(feeCode: "vat", amount: "200")]);
        def feeList2 = new ArrayList();
        feeList2.addAll([new Fee(feeCode: "comm", amount: "100"), new Fee(feeCode: "vat", amount: "200")]);

        def mapFeeList1 = new ArrayList();
        mapFeeList1.addAll([new Fee(feeCode: "comm", amount: "200"), new Fee(feeCode: "vat", amount: "200")]);
        def mapFeeList2 = new ArrayList();
        mapFeeList2.addAll([new Fee(feeCode: "comm", amount: "200"), new Fee(feeCode: "vat", amount: "200")]);

        def map1 = new HashMap<String, List<Fee>>();
        map1.put("a", mapFeeList1)

        def map2 = new HashMap<String, List<Fee>>();
        map2.put("a", mapFeeList2)


        def parent1 = new Parent(feeList: feeList1, userFeeList: map1)
        def parent2 = new Parent(feeList: feeList2, userFeeList: map2)

        when:
        def diff = javers.compare(parent1, parent2)

        println diff.prettyPrint()
        /*
        Diff:
        * changes on org.javers.core.JaversDiffE2ETest$Fee/comm :
          - 'amount' changed: '200' -> '100'
        */

        then:
        !diff.hasChanges()
    }

}
