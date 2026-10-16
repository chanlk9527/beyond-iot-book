package com.nebula.iot.device.api;
import java.util.*;
public record ProductDirectory(long revision,List<Node> items) {
 public record Node(String id,String label,List<Node> children) {
  public Node {
   if(id==null||!id.matches("[a-z]{3,4}(-[a-z]{3,4})?"))throw new IllegalArgumentException("Category ID requires 3-4 lowercase letters per segment");
   if(label==null||label.isBlank()||label.length()>40||label.contains("/")||!label.equals(label.strip()))throw new IllegalArgumentException("Invalid category name");
   children=children==null?List.of():List.copyOf(children);
  }
 }
 public ProductDirectory {
  if(revision<0||items==null||items.size()>30)throw new IllegalArgumentException("Invalid directory");
  items=List.copyOf(items);Set<String> parents=new HashSet<>(),ids=new HashSet<>();
  for(var parent:items){
   if(parent.id().contains("-")||!ids.add(parent.id())||!parents.add(parent.label())||parent.children().size()>50)throw new IllegalArgumentException("Invalid or duplicate parent category");
   Set<String> names=new HashSet<>();
   for(var child:parent.children())if(!child.id().startsWith(parent.id()+"-")||!ids.add(child.id())||!child.children().isEmpty()||!names.add(child.label()))throw new IllegalArgumentException("Invalid category hierarchy or duplicate category");
  }
 }
 public Set<String> paths(){Set<String> result=new HashSet<>();for(var p:items)for(var c:p.children())result.add(c.id());return result;}
}