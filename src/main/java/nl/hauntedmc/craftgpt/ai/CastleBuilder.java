package nl.hauntedmc.craftgpt.ai;

import com.google.gson.*;
import java.util.*;
import java.util.regex.*;

/** Local parametric architecture path: AI selects a design, Java constructs geometry. */
public final class CastleBuilder {
    private static final Gson GSON = new Gson();
    private static final Pattern SIZE = Pattern.compile("Selection is (\\d+) wide, (\\d+) high, (\\d+) deep");

    public static boolean supports(AiRequest request) {
        if (!request.schemaName().startsWith("build_program_v2") || !request.instructions().contains("CASTLE_ARCHITECTURE_V1")) return false;
        String input = request.inputText().toLowerCase(Locale.ROOT);
        if (!Pattern.compile("\\b(castle|fortress)\\b").matcher(input).find()) return false;
        var count = Pattern.compile("\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+(corner\\s+)?towers\\b").matcher(input);
        if (count.find() && !count.group(1).equals("4") && !count.group(1).equals("four")) return false;
        int[] size = dimensions(request.instructions());
        return size != null && size[0] >= 20 && size[1] >= 16 && size[2] >= 20;
    }

    public static AiResponse generate(AiClient client, ModelPreset preset, AiRequest request, int timeout) {
        int[] size = dimensions(request.instructions());
        Map<String,Object> properties = new LinkedHashMap<>();
        properties.put("wall_material", choice("stone_bricks", "cobblestone", "deepslate_bricks"));
        properties.put("trim_material", choice("chiseled_stone_bricks", "polished_andesite", "stone_bricks"));
        properties.put("roof_material", choice("spruce_planks", "dark_oak_planks", "deepslate_tiles"));
        properties.put("flag_material", choice("red_wool", "blue_wool", "white_wool"));
        properties.put("tower_height", Map.of("type","integer","minimum",8,"maximum",28));
        properties.put("roof_height", Map.of("type","integer","minimum",4,"maximum",8));
        properties.put("tower_radius", Map.of("type","integer","minimum",2,"maximum",4));
        properties.put("battlement_spacing", Map.of("type","integer","minimum",2,"maximum",4));
        var schema = Map.<String,Object>of("type","object","additionalProperties",false,
            "properties",properties,"required",new ArrayList<>(properties.keySet()));
        var payload = new LinkedHashMap<>(preset.payloadTemplate());
        payload.put("max_output_tokens",1024);
        payload.put("reasoning",Map.of("effort","none"));
        var designPreset = new ModelPreset(preset.name(),preset.endpoint(),preset.apiKey(),payload);
        var response = client.execute(designPreset,new AiRequest("castle_design_v1",
            "Choose a coherent material palette and proportions for a complete four-tower medieval castle. " +
            "Java constructs all geometry, including hollow towers, finished pointed roofs, flags, crenellations, courtyard, gatehouse and entrances. " +
            "Do not output coordinates or DSL. Return only the requested design parameters. Selection: " + Arrays.toString(size),
            request.inputText(),schema,List.of()),timeout);
        if (!response.success()) return response;
        try {
            var design = JsonParser.parseString(response.content()).getAsJsonObject();
            return AiResponse.success(build(size[0],size[1],size[2],design),response.rawResponse());
        } catch (RuntimeException error) {
            return AiResponse.failure("Invalid castle design parameters: " + error.getMessage(),response.rawResponse(),true);
        }
    }

    public static int[] dimensions(String instructions) {
        var match = SIZE.matcher(instructions);
        return match.find() ? new int[]{Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))} : null;
    }

    public static Map<String,Object> choice(String... values) { return Map.of("type","string","enum",List.of(values)); }
    private static String material(JsonObject design,String key,String fallback,String... allowed) {
        String value = design.has(key) ? design.get(key).getAsString().replace("minecraft:","") : fallback;
        return "minecraft:" + (Arrays.asList(allowed).contains(value) ? value : fallback);
    }
    private static int number(JsonObject design,String key,int fallback,int min,int max) {
        return Math.max(min,Math.min(max,design.has(key) ? design.get(key).getAsInt() : fallback));
    }
    private static List<Integer> v(int x,int y,int z) { return List.of(x,y,z); }
    private static Map<String,Object> box(String id,int x,int y,int z,int X,int Y,int Z) {
        var op = new LinkedHashMap<String,Object>();
        op.put("k","box"); op.put("m",id == null ? "cut" : "add");
        if (id != null) op.put("i",id);
        op.put("f",v(x,y,z));op.put("t",v(X,Y,Z));op.put("ho",false);op.put("wt",1);
        return op;
    }
    private static Map<String,Object> cyl(String id,int x,int y,int z,int r,int h,boolean hollow) {
        return Map.of("k","cyl","m","add","i",id,"c",v(x,y,z),"r",r,"h",h,"ax","y","ho",hollow,"wt",1);
    }
    private static Map<String,Object> instance(String component,int x,int y,int z) {
        return Map.of("c",component,"at",v(x,y,z),"r",0,"mx",false,"mz",false);
    }
    private static Map<String,Object> point(String id,int x,int y,int z) {
        return Map.of("k","pt","m","add","i",id,"at",v(x,y,z));
    }

    public static String build(int width,int height,int depth,JsonObject design) {
        if (width < 20 || depth < 20 || height < 16) throw new IllegalArgumentException("Castle requires at least 20x16x20");
        int maxRadius = Math.min(4,(Math.min(width,depth)-8)/6);
        int radius = number(design,"tower_radius",3,2,maxRadius);
        int roofRadius = radius+1;
        int roofHeight = number(design,"roof_height",6,4,Math.min(8,height-12));
        int towerHeight = number(design,"tower_height",20,6,height-roofHeight-6);
        int wallHeight = Math.max(4,(int)Math.round(towerHeight*0.55));
        int spacing = number(design,"battlement_spacing",3,2,4);
        int left = 2+roofRadius, right = width-3-roofRadius;
        int front = 2+roofRadius, back = depth-3-roofRadius;
        int middle = (left+right)/2;
        int gateHalf = Math.min(5,Math.max(2,(right-left-2*radius)/2-1));
        int gateHeight = Math.min(wallHeight+3,towerHeight-1);
        var palette = new ArrayList<Map<String,Object>>();
        palette.add(Map.of("i","1","b",material(design,"wall_material","stone_bricks","stone_bricks","cobblestone","deepslate_bricks")));
        palette.add(Map.of("i","2","b",material(design,"trim_material","chiseled_stone_bricks","chiseled_stone_bricks","polished_andesite","stone_bricks")));
        palette.add(Map.of("i","3","b",material(design,"roof_material","spruce_planks","spruce_planks","dark_oak_planks","deepslate_tiles")));
        palette.add(Map.of("i","4","b","minecraft:cobblestone"));
        palette.add(Map.of("i","5","b","minecraft:oak_planks"));
        palette.add(Map.of("i","6","b","minecraft:iron_bars"));
        palette.add(Map.of("i","7","b","minecraft:lantern"));
        palette.add(Map.of("i","8","b",material(design,"flag_material","red_wool","red_wool","blue_wool","white_wool")));
        palette.add(Map.of("i","9","b","minecraft:oak_fence"));

        var tower = new ArrayList<Map<String,Object>>();
        int center=roofRadius;
        tower.add(cyl("4",center,0,center,roofRadius,2,false));
        tower.add(cyl("1",center,2,center,radius,towerHeight-2,true));
        for(int y=2;y<towerHeight;y+=5) tower.add(cyl("5",center,y,center,Math.max(1,radius-1),1,false));
        tower.add(cyl("2",center,towerHeight-1,center,radius,1,true));
        for(int y=5;y<towerHeight-2;y+=6) {
            tower.add(box(null,center,y,center-radius,center,y+1,center-radius));
            tower.add(box("6",center,y,center-radius,center,y+1,center-radius));
            tower.add(box(null,center,y,center+radius,center,y+1,center+radius));
            tower.add(box("6",center,y,center+radius,center,y+1,center+radius));
            tower.add(box(null,center-radius,y,center,center-radius,y+1,center));
            tower.add(box("6",center-radius,y,center,center-radius,y+1,center));
            tower.add(box(null,center+radius,y,center,center+radius,y+1,center));
            tower.add(box("6",center+radius,y,center,center+radius,y+1,center));
        }
        // Each roof narrows monotonically and has a complete peak.
        for(int y=0;y<roofHeight;y++) {
            int r=Math.max(1,(int)Math.ceil(roofRadius*(1.0-(double)y/roofHeight)));
            tower.add(cyl("3",center,towerHeight+y,center,r,1,false));
        }
        int peak=towerHeight+roofHeight;
        tower.add(point("3",center,peak,center));
        tower.add(box("9",center,peak+1,center,center,peak+3,center));
        tower.add(box("8",center+1,peak+2,center,center+2,peak+3,center));
        var components=new ArrayList<Map<String,Object>>();
        components.add(Map.of("n","tower","a",tower));
        components.add(Map.of("n","merlon_x","a",List.of(box("2",0,0,0,0,1,1))));
        components.add(Map.of("n","merlon_z","a",List.of(box("2",0,0,0,1,1,0))));
        var instances=new ArrayList<Map<String,Object>>();
        for(int x:new int[]{left,right}) for(int z:new int[]{front,back}) instances.add(instance("tower",x-center,0,z-center));

        var actions=new ArrayList<Map<String,Object>>();
        // Courtyard and walls are thin; there is no massive solid foundation.
        actions.add(box("4",left,0,front,right,0,back));
        actions.add(box("1",left,1,front,right,wallHeight-1,front+1));
        actions.add(box("1",left,1,back-1,right,wallHeight-1,back));
        actions.add(box("1",left,1,front,left+1,wallHeight-1,back));
        actions.add(box("1",right-1,1,front,right,wallHeight-1,back));
        for(int y:new int[]{2,wallHeight-1}) {
            actions.add(box("2",left,y,front,right,y,front+1));
            actions.add(box("2",left,y,back-1,right,y,back));
            actions.add(box("2",left,y,front,left+1,y,back));
            actions.add(box("2",right-1,y,front,right,y,back));
        }
        for(int x=left+radius+1;x<right-radius;x+=spacing) {
            if(Math.abs(x-middle)>gateHalf+1) instances.add(instance("merlon_x",x,wallHeight,front));
            instances.add(instance("merlon_x",x,wallHeight,back-1));
        }
        for(int z=front+radius+1;z<back-radius;z+=spacing) {
            instances.add(instance("merlon_z",left,wallHeight,z));
            instances.add(instance("merlon_z",right-1,wallHeight,z));
        }
        // Hollow gatehouse, a clear passage through the front wall, and a finished pitched roof.
        actions.add(box("1",middle-gateHalf,1,front-2,middle+gateHalf,gateHeight,front+3));
        actions.add(box(null,middle-gateHalf+1,1,front-1,middle+gateHalf-1,gateHeight-1,front+2));
        int doorwayHalf=Math.min(2,gateHalf-1);
        actions.add(box(null,middle-doorwayHalf,1,front-2,middle+doorwayHalf,Math.min(5,gateHeight-1),front+3));
        actions.add(box("2",middle-doorwayHalf-1,1,front-2,middle-doorwayHalf-1,Math.min(6,gateHeight),front-2));
        actions.add(box("2",middle+doorwayHalf+1,1,front-2,middle+doorwayHalf+1,Math.min(6,gateHeight),front-2));
        actions.add(box("2",middle-doorwayHalf-1,Math.min(6,gateHeight),front-2,middle+doorwayHalf+1,Math.min(6,gateHeight),front-2));
        for(int layer=0;layer<=gateHalf;layer++) actions.add(box("3",middle-gateHalf-1+layer,gateHeight+1+layer,front-3,middle+gateHalf+1-layer,gateHeight+1+layer,front+4));
        actions.add(point("7",middle-gateHalf,3,front-3));
        actions.add(point("7",middle+gateHalf,3,front-3));
        actions.add(box("2",middle-doorwayHalf,0,0,middle+doorwayHalf,0,back-1));
        return GSON.toJson(Map.of("v",2,"o",v(0,0,0),"p",palette,"c",components,"i",instances,"a",actions));
    }
}
