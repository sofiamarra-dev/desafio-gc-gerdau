package com.example.demo.service;

import java.util.*;

public final class CatalogPages {
    private CatalogPages() {}
    public static int number(Map<String,String> params,String name,int fallback,int maximum) {
        try { return Math.clamp(Integer.parseInt(params.getOrDefault(name,""+fallback)),1,maximum); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Paginação inválida."); }
    }
    public static Map<String,Object> page(List<Map<String,Object>> source,Map<String,String> params) {
        String query=params.getOrDefault("query","").strip().toLowerCase(Locale.ROOT);
        var rows=source.stream().filter(row -> {
            if (!query.isBlank() && !(value(row,"descricao_item")+" "+value(row,"codigo_servico")).toLowerCase(Locale.ROOT).contains(query)) return false;
            for(String field:List.of("grupo_servico","codigo_fornecedor","unidade_medida","verification_status")) {
                String filter=params.getOrDefault(field,"").strip();
                if(!filter.isBlank() && !value(row,field).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) return false;
            }
            return !"true".equals(params.get("pending_only")) || !Boolean.TRUE.equals(row.get("reviewed"));
        }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        String sort=params.getOrDefault("sort","similarity");
        if(!Set.of("similarity","codigo_servico","descricao_item","grupo_servico","codigo_fornecedor","unidade_medida","verification_status","created_at").contains(sort)) throw new IllegalArgumentException("Ordenação inválida.");
        Comparator<Map<String,Object>> comparator;
        if("similarity".equals(sort)) {
            comparator=Comparator.<Map<String,Object>,Boolean>comparing(row -> Boolean.TRUE.equals(row.get("contains"))).reversed()
                    .thenComparing(Comparator.<Map<String,Object>>comparingDouble(row -> row.get("similarity") instanceof Number n?n.doubleValue():-2).reversed());
        } else {
            comparator=Comparator.comparing(row -> value(row,sort),String.CASE_INSENSITIVE_ORDER);
            if("desc".equals(params.get("order"))) comparator=comparator.reversed();
        }
        rows.sort(comparator.thenComparing(row -> value(row,"catalog_id")));
        int size=number(params,"size",20,100),page=number(params,"page",1,Integer.MAX_VALUE);
        int pages=Math.max(1,(rows.size()+size-1)/size);page=Math.min(page,pages);
        int start=(page-1)*size;
        return new LinkedHashMap<>(Map.of("items",rows.subList(start,Math.min(start+size,rows.size())),"total",rows.size(),"page",page,"size",size,"pages",pages));
    }
    private static String value(Map<String,Object> row,String key) { return Objects.toString(row.get(key),""); }
}
