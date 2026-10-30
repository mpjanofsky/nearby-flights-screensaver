// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.flights.nativeview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WeatherTextTest {
  private static Model parse(String wx) throws Exception {
    return Model.parse("{\"v\":2,\"gen\":1,\"ac\":[]" + wx + "}", 1);
  }

  @Test
  void theLineIsTheTemperatureThenTheConditionAndFallsBackToTheTemperature() throws Exception {
    Model m = parse(",\"wx\":{\"c\":\"partlycloudy\",\"t\":22.2,\"tu\":\"F\"}");
    assertArrayEquals(new String[] {"72° Partly cloudy", "72°"}, WeatherText.candidates(m));
  }

  @Test
  void celsiusIsShownInCelsiusAndAnUnknownConditionKeepsTheTemperature() throws Exception {
    assertArrayEquals(
        new String[] {"19° Sunny", "19°"},
        WeatherText.candidates(parse(",\"wx\":{\"c\":\"sunny\",\"t\":18.6,\"tu\":\"C\"}")));
    assertArrayEquals(
        new String[] {"20°"}, WeatherText.candidates(parse(",\"wx\":{\"t\":20,\"tu\":\"C\"}")));
  }

  @Test
  void chosenPiecesDecideTheLine() throws Exception {
    String base = ",\"wx\":{\"c\":\"sunny\",\"t\":18.6,\"tu\":\"C\",\"sh\":\"";
    assertArrayEquals(
        new String[] {"19° Sunny", "19°"}, WeatherText.candidates(parse(base + "tci\"}")));
    assertArrayEquals(new String[] {"19°"}, WeatherText.candidates(parse(base + "t\"}")));
    assertArrayEquals(new String[] {"Sunny"}, WeatherText.candidates(parse(base + "c\"}")));
    assertArrayEquals(new String[] {""}, WeatherText.candidates(parse(base + "i\"}")));
    assertNull(WeatherText.candidates(parse(base + "\"}")));
  }

  @Test
  void theIconNeedsBothTheChoiceAndAKnownCondition() throws Exception {
    String wx = ",\"wx\":{\"c\":\"sunny\",\"t\":20,\"tu\":\"C\"";
    assertEquals(true, WeatherText.hasIcon(parse(wx + "}")));
    assertEquals(false, WeatherText.hasIcon(parse(wx + ",\"sh\":\"tc\"}")));
    assertEquals(
        false, WeatherText.hasIcon(parse(",\"wx\":{\"t\":20,\"tu\":\"C\",\"sh\":\"ti\"}")));
  }

  private static final String WIND =
      ",\"wx\":{\"c\":\"partlycloudy\",\"t\":22.2,\"tu\":\"F\",\"w\":3.6,\"wu\":\"mph\",";

  @Test
  void windIsAddedAndItsDirectionThenItThenTheWordGiveWayInThatOrder() throws Exception {
    Model m = parse(WIND + "\"wd\":315,\"sh\":\"tcwd\"}");
    assertArrayEquals(
        new String[] {
          "72° Partly cloudy · NW 8 mph", "72° Partly cloudy · 8 mph", "72° Partly cloudy", "72°"
        },
        WeatherText.candidates(m));
  }

  @Test
  void theDotSeparatesTheWordFromTheWindOnly() throws Exception {
    // No word: the temperature and the wind sit side by side without a dot.
    assertArrayEquals(
        new String[] {"72° NW 8 mph", "72° 8 mph", "72°"},
        WeatherText.candidates(parse(WIND + "\"wd\":315,\"sh\":\"twd\"}")));
  }

  @Test
  void speedAloneHasNoDirectionAndWindIsOffUnlessChosen() throws Exception {
    assertArrayEquals(
        new String[] {"72° Partly cloudy · 8 mph", "72° Partly cloudy", "72°"},
        WeatherText.candidates(parse(WIND + "\"wd\":315,\"sh\":\"tcw\"}")));
    assertArrayEquals(
        new String[] {"72° Partly cloudy", "72°"},
        WeatherText.candidates(parse(WIND + "\"wd\":315}")));
  }

  @Test
  void windWorksWithoutTheTemperatureOrTheWord() throws Exception {
    assertArrayEquals(
        new String[] {"NW 8 mph", "8 mph"},
        WeatherText.candidates(parse(WIND + "\"wd\":315,\"sh\":\"wd\"}")));
    assertArrayEquals(
        new String[] {"Partly cloudy · NW 8 mph", "Partly cloudy · 8 mph", "Partly cloudy"},
        WeatherText.candidates(parse(WIND + "\"wd\":315,\"sh\":\"cwd\"}")));
  }

  @Test
  void anUnknownDirectionOrNoWindDataLeavesTheRestOfTheLine() throws Exception {
    assertArrayEquals(
        new String[] {"72° Partly cloudy · 8 mph", "72° Partly cloudy", "72°"},
        WeatherText.candidates(parse(WIND + "\"sh\":\"tcwd\"}")));
    assertArrayEquals(
        new String[] {"72° Partly cloudy", "72°"},
        WeatherText.candidates(
            parse(",\"wx\":{\"c\":\"partlycloudy\",\"t\":22.2,\"tu\":\"F\",\"sh\":\"tcwd\"}")));
  }

  @Test
  void aCalmWindSaysCalmAndTheCompassHasEightPoints() throws Exception {
    Model m =
        parse(",\"wx\":{\"t\":20,\"tu\":\"C\",\"w\":0.1,\"wu\":\"km/h\",\"wd\":90,\"sh\":\"wd\"}");
    assertArrayEquals(new String[] {"Calm"}, WeatherText.candidates(m));
    assertEquals("N", WeatherText.compass(0));
    assertEquals("N", WeatherText.compass(359));
    assertEquals("NE", WeatherText.compass(44));
    assertEquals("E", WeatherText.compass(90));
    assertEquals("SW", WeatherText.compass(225));
    assertEquals("NW", WeatherText.compass(-45));
  }

  @Test
  void noWeatherInThePayloadMeansNoLine() throws Exception {
    assertNull(WeatherText.candidates(parse("")));
  }

  @Test
  void aSmallNegativeIsNeverMinusZero() {
    assertEquals("0°", WeatherText.temp(-0.2, false));
    assertEquals("-5°", WeatherText.temp(-5, false));
  }

  @Test
  void everyHaConditionHasAWordAndAnIcon() {
    for (String c :
        new String[] {
          "clear-night",
          "cloudy",
          "exceptional",
          "fog",
          "hail",
          "lightning",
          "lightning-rainy",
          "partlycloudy",
          "pouring",
          "rainy",
          "snowy",
          "snowy-rainy",
          "sunny",
          "windy",
          "windy-variant"
        }) {
      assertEquals(false, WeatherText.label(c) == null, c);
      assertEquals(false, WeatherText.icon(c) == null, c);
    }
    assertNull(WeatherText.icon("volcanic-ash"));
  }
}
