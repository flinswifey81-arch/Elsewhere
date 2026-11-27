package com.example.phonelab.systems;
import static com.example.phonelab.systems.PhonePolicy.*;
import java.util.List;
public final class PhonePolicySmoke {
    public static void main(String[] args) {
        Scope a = new Scope("world","campaign-a","lilly");
        Scope b = new Scope("world","campaign-b","lilly");
        Scope otherPersona = new Scope("world","campaign-a","other");
        ThreadKey lead = new ThreadKey(a,"bakugo");
        ThreadKey npc = new ThreadKey(a,"mika");
        Carry m = new Carry("m",lead,Destination.STORY_MEMORY,"Meet at eight",List.of("line-1"),3);
        Carry n = new Carry("n",npc,Destination.STORY_MEMORY,"Drill at nine",List.of("line-2"),2);
        assert "Meet at eight".equals(m.storyText(a,"bakugo",4));
        assert "Meet at eight".equals(m.storyText(a,"lilly",3));
        assert m.storyText(a,"mika",4) == null;
        assert m.storyText(a,"bakugo",2) == null;
        assert m.storyText(b,"bakugo",8) == null;
        assert m.storyText(otherPersona,"bakugo",8) == null;
        assert "Drill at nine".equals(n.storyText(a,"mika",4));
        assert n.storyText(a,"bakugo",4) == null;
        Carry lore = new Carry("l",lead,Destination.PLAYTHROUGH_LORE,"There is a rooftop garden",List.of("line-1"),3);
        assert lore.loreFor(a,4) != null;
        assert lore.loreFor(a,2) == null;
        assert lore.loreFor(b,4) == null;
        Carry draft = new Carry("d",lead,Destination.WORLD_CANON_DRAFT,"There is a rooftop garden",List.of("line-1"),3);
        assert draft.loreFor(a,4) == null && draft.storyText(a,"bakugo",4) == null;
        System.out.println("Phone policy checks passed");
    }
}
