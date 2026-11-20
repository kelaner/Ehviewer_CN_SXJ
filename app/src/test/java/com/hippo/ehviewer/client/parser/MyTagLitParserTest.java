package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.userTag.UserTagList;
import com.hippo.ehviewer.client.exception.EhException;

import org.junit.Test;

public class MyTagLitParserTest {
    @Test
    public void parsesWatchedArtistsAfterEmptyWeight() throws Exception {
        String html = "<div id='usertags_outer'><div id='usertag_0'></div>"
                + "<div id='usertag_1'><div id='tagpreview_1' title='artist:first'></div>"
                + "<input id='tagwatch_1' checked><input id='tagcolor_1'><input id='tagweight_1' value=''></div>"
                + "<div id='usertag_2'><div id='tagpreview_2' title='artist:second'></div>"
                + "<input id='tagwatch_2' checked><input id='tagcolor_2'><input id='tagweight_2' value='10'></div></div>";
        UserTagList result = MyTagLitParser.parse(html);
        assertEquals(2, result.userTags.size());
        assertEquals("artist:second", result.userTags.get(1).tagName);
        assertTrue(result.userTags.get(0).watched);
        assertTrue(result.userTags.get(1).watched);
    }

    @Test
    public void readsAvailableAndSelectedTagSets() throws Exception {
        String html = "<div id='tagset_outer'><select>"
                + "<option value='1'>First</option><option value='2' selected>Second</option>"
                + "</select></div><div id='usertags_outer'><div id='usertag_0'></div>"
                + "<div id='usertag_42'><div id='tagpreview_42' title='artist:hatome'></div>"
                + "<input id='tagwatch_42' checked><input id='tagcolor_42'><input id='tagweight_42' value='10'></div></div>";
        UserTagList result = MyTagLitParser.parse(html);
        assertEquals(java.util.Arrays.asList("1", "2"), result.tagSetIds);
        assertEquals("2", result.currentTagSetId);
        assertEquals("artist:hatome", result.userTags.get(0).tagName);
        assertTrue(result.userTags.get(0).watched);
    }

    @Test(expected = EhException.class)
    public void rejectsPageWithoutSubscriptionList() throws Exception {
        MyTagLitParser.parse("<html><body>Login required</body></html>");
    }
}
