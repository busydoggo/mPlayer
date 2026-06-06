package app.marlboroadvance.mpvex.ui.player.danmaku;

import android.graphics.Color;
import android.text.TextUtils;
import java.io.IOException;
import java.util.Locale;
import master.flame.danmaku.danmaku.model.AlphaValue;
import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.Duration;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.SpecialDanmaku;
import master.flame.danmaku.danmaku.model.android.DanmakuFactory;
import master.flame.danmaku.danmaku.model.android.Danmakus;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.danmaku.parser.android.AndroidFileSource;
import master.flame.danmaku.danmaku.util.DanmakuUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

import static master.flame.danmaku.danmaku.model.IDanmakus.ST_BY_TIME;

public class BiliDanmakuParser extends BaseDanmakuParser {
  static {
    System.setProperty("org.xml.sax.driver", "org.xmlpull.v1.sax2.Driver");
  }

  private float dispScaleX;
  private float dispScaleY;

  @Override
  public Danmakus parse() {
    if (mDataSource != null) {
      AndroidFileSource source = (AndroidFileSource) mDataSource;
      try {
        XMLReader xmlReader = XMLReaderFactory.createXMLReader();
        XmlContentHandler contentHandler = new XmlContentHandler();
        xmlReader.setContentHandler(contentHandler);
        xmlReader.parse(new InputSource(source.data()));
        return contentHandler.getResult();
      } catch (SAXException | IOException e) {
        e.printStackTrace();
      }
    }
    return new Danmakus(ST_BY_TIME, false, mContext.getBaseComparator());
  }

  private class XmlContentHandler extends DefaultHandler {
    private static final String TRUE_STRING = "true";

    private Danmakus result;
    private BaseDanmaku item;
    private int index;

    Danmakus getResult() {
      return result;
    }

    @Override
    public void startDocument() {
      result = new Danmakus(ST_BY_TIME, false, mContext.getBaseComparator());
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      String tagName = localName.length() != 0 ? localName : qName;
      tagName = tagName.toLowerCase(Locale.getDefault()).trim();
      if (!"d".equals(tagName)) {
        return;
      }

      String pValue = attributes.getValue("p");
      if (pValue == null) {
        return;
      }

      String[] values = pValue.split(",");
      if (values.length < 4) {
        return;
      }

      long time = (long) (parseFloat(values[0]) * 1000);
      int type = parseInteger(values[1]);
      float textSize = parseFloat(values[2]);
      int color = (int) ((0x00000000ff000000L | parseLong(values[3])) & 0x00000000ffffffffL);
      item = mContext.mDanmakuFactory.createDanmaku(type, mContext);
      if (item != null) {
        item.setTime(time);
        item.textSize = textSize * (mDispDensity - 0.6f);
        item.textColor = color;
        item.textShadowColor = color <= Color.BLACK ? Color.WHITE : Color.BLACK;
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      if (item == null || item.text == null || item.duration == null) {
        item = null;
        return;
      }

      String tagName = localName.length() != 0 ? localName : qName;
      if (tagName.equalsIgnoreCase("d")) {
        item.setTimer(mTimer);
        item.flags = mContext.mGlobalFlagValues;
        Object lock = result.obtainSynchronizer();
        synchronized (lock) {
          result.addItem(item);
        }
      }
      item = null;
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (item == null) {
        return;
      }

      String text = decodeXmlString(new String(ch, start, length));
      if (isScrollingDanmaku(item)) {
        text = formatTimestamp(item.getTime()) + " " + text;
      }
      DanmakuUtils.fillText(item, text);
      item.index = index++;

      String parsedText = String.valueOf(item.text).trim();
      if (item.getType() == BaseDanmaku.TYPE_SPECIAL && parsedText.startsWith("[") && parsedText.endsWith("]")) {
        parseSpecialDanmaku(parsedText);
      }
    }

    private void parseSpecialDanmaku(String text) {
      String[] textArr = null;
      try {
        JSONArray jsonArray = new JSONArray(text);
        textArr = new String[jsonArray.length()];
        for (int i = 0; i < textArr.length; i++) {
          textArr[i] = jsonArray.getString(i);
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }

      if (textArr == null || textArr.length < 5 || TextUtils.isEmpty(textArr[4])) {
        item = null;
        return;
      }

      DanmakuUtils.fillText(item, textArr[4]);
      float beginX = parseFloat(textArr[0]);
      float beginY = parseFloat(textArr[1]);
      float endX = beginX;
      float endY = beginY;
      String[] alphaArr = textArr[2].split("-");
      int beginAlpha = (int) (AlphaValue.MAX * parseFloat(alphaArr[0]));
      int endAlpha = beginAlpha;
      if (alphaArr.length > 1) {
        endAlpha = (int) (AlphaValue.MAX * parseFloat(alphaArr[1]));
      }
      long alphaDuration = (long) (parseFloat(textArr[3]) * 1000);
      long translationDuration = alphaDuration;
      long translationStartDelay = 0;
      float rotateY = 0;
      float rotateZ = 0;

      if (textArr.length >= 7) {
        rotateZ = parseFloat(textArr[5]);
        rotateY = parseFloat(textArr[6]);
      }
      if (textArr.length >= 11) {
        endX = parseFloat(textArr[7]);
        endY = parseFloat(textArr[8]);
        if (!"".equals(textArr[9])) {
          translationDuration = parseInteger(textArr[9]);
        }
        if (!"".equals(textArr[10])) {
          translationStartDelay = (long) parseFloat(textArr[10]);
        }
      }

      if (isPercentageNumber(textArr[0])) beginX *= DanmakuFactory.BILI_PLAYER_WIDTH;
      if (isPercentageNumber(textArr[1])) beginY *= DanmakuFactory.BILI_PLAYER_HEIGHT;
      if (textArr.length >= 8 && isPercentageNumber(textArr[7])) endX *= DanmakuFactory.BILI_PLAYER_WIDTH;
      if (textArr.length >= 9 && isPercentageNumber(textArr[8])) endY *= DanmakuFactory.BILI_PLAYER_HEIGHT;

      item.duration = new Duration(alphaDuration);
      item.rotationZ = rotateZ;
      item.rotationY = rotateY;
      mContext.mDanmakuFactory.fillTranslationData(
        item,
        beginX,
        beginY,
        endX,
        endY,
        translationDuration,
        translationStartDelay,
        dispScaleX,
        dispScaleY
      );
      mContext.mDanmakuFactory.fillAlphaData(item, beginAlpha, endAlpha, alphaDuration);

      if (textArr.length >= 12 && !TextUtils.isEmpty(textArr[11]) && TRUE_STRING.equalsIgnoreCase(textArr[11])) {
        item.textShadowColor = Color.TRANSPARENT;
      }
      if (textArr.length >= 14) {
        ((SpecialDanmaku) item).isQuadraticEaseOut = "0".equals(textArr[13]);
      }
      if (textArr.length >= 15 && !"".equals(textArr[14])) {
        parseSpecialDanmakuPath(textArr[14]);
      }
    }

    private void parseSpecialDanmakuPath(String motionPath) {
      String motionPathString = motionPath.substring(1);
      if (TextUtils.isEmpty(motionPathString)) {
        return;
      }

      String[] pointStrArray = motionPathString.split("L");
      float[][] points = new float[pointStrArray.length][2];
      for (int i = 0; i < pointStrArray.length; i++) {
        String[] pointArray = pointStrArray[i].split(",");
        if (pointArray.length >= 2) {
          points[i][0] = parseFloat(pointArray[0]);
          points[i][1] = parseFloat(pointArray[1]);
        }
      }
      mContext.mDanmakuFactory.fillLinePathData(item, points, dispScaleX, dispScaleY);
    }

    private String decodeXmlString(String title) {
      return title
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&gt;", ">")
        .replace("&lt;", "<");
    }
  }

  private boolean isPercentageNumber(String number) {
    return number != null && number.contains(".");
  }

  private boolean isScrollingDanmaku(BaseDanmaku danmaku) {
    int type = danmaku.getType();
    return type == BaseDanmaku.TYPE_SCROLL_RL || type == BaseDanmaku.TYPE_SCROLL_LR;
  }

  private String formatTimestamp(long timeMs) {
    long minutes = timeMs / 60000;
    long seconds = (timeMs / 1000) % 60;
    long millis = timeMs % 1000;
    return String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, millis);
  }

  private float parseFloat(String floatStr) {
    try {
      return Float.parseFloat(floatStr);
    } catch (NumberFormatException e) {
      return 0.0f;
    }
  }

  private int parseInteger(String intStr) {
    try {
      return Integer.parseInt(intStr);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private long parseLong(String longStr) {
    try {
      return Long.parseLong(longStr);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public BaseDanmakuParser setDisplayer(IDisplayer disp) {
    super.setDisplayer(disp);
    dispScaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH;
    dispScaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT;
    return this;
  }
}
