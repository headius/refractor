require 'java'

r = com.headius.refractor.Refractor.new
r.build([java.util.ArrayList, java.util.AbstractList, java.util.Collection.java_class, java.lang.Number, java.lang.Integer, java.lang.String, java.lang.Object].to_java(java.lang.Class), false)
