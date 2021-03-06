package me.kitskub.hungergames.stats;


import java.io.IOException;
import java.sql.Date;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import me.kitskub.hungergames.Defaults;
import me.kitskub.hungergames.HungerGames;
import me.kitskub.hungergames.Logging;
import me.kitskub.hungergames.games.HungerGame;
import me.kitskub.hungergames.stats.PlayerStat.PlayerState;
import me.kitskub.hungergames.utils.ConnectionUtils;
import org.bukkit.Bukkit;

public class GameStats {
	private final HungerGame game;
	private final List<Death> deaths = new ArrayList<Death>();
	private final List<PlayerStat> players = new ArrayList<PlayerStat>();
	private final Map<String, String> map = new HashMap<String, String>();
	
	public GameStats(HungerGame game){
		this.game = game;
	}
	
	public void saveGameData(){
		saveGameData(this.game);
	}
	
	public void saveGameData(HungerGame game){
		map.put("requestType", "updateGameDetails");
		map.put("startTime", String.valueOf(game.getInitialStartTime()));
		map.put("name", game.getName());
		
		map.put("totalPlayers", String.valueOf(game.getAllPlayers().size()));
		if (game.getRemainingPlayers().size() != 1) {
			map.put("winner", "N/A");
		}
		else {
			map.put("winner", game.getRemainingPlayers().get(0).getName());
		}
		long totalDuration = 0;
		for (int i = 0; i < Math.min(game.getEndTimes().size(), game.getStartTimes().size()); i++) {
			totalDuration += game.getEndTimes().get(i) - game.getStartTimes().get(i);
		}
		map.put("totalDuration", String.valueOf(totalDuration));
	}
	
	public void addPlayer(PlayerStat playerStat){
		players.add(playerStat);
	}
	
	public void addAllPlayer(Collection<PlayerStat> playerStats){
		players.addAll(playerStats);
	}
	
	public void addDeath(String player, String killer, String cause){
		deaths.add(new Death(player, killer, cause,  System.currentTimeMillis() / 1000));
	}
	
	public void addDeath(Death death){
		deaths.add(death);
	}
	
	public void submit(){//Also clears
		final Map<String, String> localMap = new HashMap<String, String>(map);
		map.clear();
		final String urlString = Defaults.Config.WEBSTATS_IP.getGlobalString();
		if ("0.0.0.0".equals(urlString)) return;
		StringBuilder playersSB = new StringBuilder();
		for (PlayerStat p : players) {
			playersSB.append("{").append(p.getPlayer().getName()).append("}");
			String k = "players[" + p.getPlayer().getName() + "]";
			localMap.put(k + "[wins]", p.getState() == PlayerState.DEAD ? "0" : "1");
			localMap.put(k + "[deaths]", String.valueOf(p.getNumDeaths()));
			localMap.put(k + "[kills]", String.valueOf(p.getNumKills()));
			localMap.put(k + "[time]", new Time(p.getTime()).toString());
		}
		players.clear();
		localMap.put("players", playersSB.toString());
		StringBuilder sponsorsSB = new StringBuilder();
		for (String s : game.getSponsors().keySet()) {
			sponsorsSB.append("{").append(s).append(":");
			for (String sponsee : game.getSponsors().get(s)) sponsorsSB.append("{").append(sponsee).append("}");
			sponsorsSB.append("}");
		}
		localMap.put("sponsors", sponsorsSB.toString());
		localMap.put("deaths", String.valueOf(deaths.size()));
		int j = 0;
		for (Death d : deaths) {
			String k = "deaths[" + (j++) + "]";
			localMap.put(k + "[time]", String.valueOf(d.getTime()));
			localMap.put(k + "[player]", d.getPlayer());
			localMap.put(k + "[killer]", d.getKiller() == null ? "" : d.getKiller());	
			localMap.put(k + "[cause]", d.getCause());		
		}
		deaths.clear();
		Bukkit.getScheduler().runTaskAsynchronously(HungerGames.getInstance(), new Runnable() {
			public void run() {
				try {
					ConnectionUtils.post(urlString, localMap);
				} catch (ParserConfigurationException ex) {
					Logging.debug("Error when updating games: " + ex.getMessage());
				} catch (SAXException ex) {
				} catch (IOException ex) {
					Logging.debug("Error when updating games: " + ex.getMessage());
				}
			}
		});
	}
	
	public static SQLStat getStat(String s) {
		String urlString = Defaults.Config.WEBSTATS_IP.getGlobalString();
		if ("0.0.0.0".equals(urlString)) return null;
		Map<String, String> map = new HashMap<String, String>();
		map.put("requestType", "requestPlayer");
		map.put("playerName", s);
		Document doc;
		try {
			doc = ConnectionUtils.postWithRequest(urlString, map);
		} catch (ParserConfigurationException ex) {
			Logging.debug("Error when getting stat: " + ex.getMessage());
			return null;
		} catch (SAXException ex) {
			Logging.debug("Error when getting stat: " + ex.getMessage());
			return null;
		} catch (IOException ex) {
			Logging.debug("Error when getting stat: " + ex.getMessage());
			return null;
		}
		SQLStat stat = null;
		Element rootEle = doc.getDocumentElement();
		NodeList globalElems = rootEle.getElementsByTagName("global");
		if (globalElems.getLength() > 0) {
			stat = new SQLStat();
			Node node = globalElems.item(0);
			stat.rank = getIntValue(node, "rank");
			stat.deaths = getIntValue(node, "deaths");
			stat.kills = getIntValue(node, "kills");
			stat.lastLogin = getDateValue(node, "lastLogin");
			stat.totalGames = getIntValue(node, "totalGames");
			stat.totalTime = getTimeValue(node, "totalTime");
			stat.wins = getIntValue(node, "wins");
		}
		if (stat == null) return null;
		NodeList gamesElems = rootEle.getElementsByTagName("game");
		for (int i = 0; i < gamesElems.getLength(); i++) {
			Node node = gamesElems.item(i);
			SQLStat.SQLGameStat gameStat = stat.new SQLGameStat();
			gameStat.startTime = getDateValue(node, "startTime");
			gameStat.totalDuration = getTimeValue(node, "totalDuration");
			gameStat.totalPlayers = getIntValue(node, "totalPlayers");
			gameStat.winner = getTextValue(node, "winner");
			NodeList playersElems = ((Element) node).getElementsByTagName("player");
			for (int j = 0; j < playersElems.getLength(); j++) {
				gameStat.players.add(playersElems.item(j).getNodeValue());
			}
			NodeList sponsorsElems = ((Element) node).getElementsByTagName("sponsor");
			for (int j = 0; j < playersElems.getLength(); j++) {
				gameStat.players.add(sponsorsElems.item(j).getNodeValue());
			}
		}
		return stat;
	}
	
	private static String getTextValue(Node node, String tagName) {
		String textVal = null;
		Element ele = (Element) node;
		NodeList nl = ele.getElementsByTagName(tagName);
		if(nl != null && nl.getLength() > 0) {
			Element el = (Element)nl.item(0);
			textVal = el.getFirstChild().getNodeValue();
		}

		return textVal;
	}
	
	private static Integer getIntValue(Node node, String tagName) {
		String textVal = null;
		Element ele = (Element) node;
		NodeList nl = ele.getElementsByTagName(tagName);
		if(nl != null && nl.getLength() > 0) {
			Element el = (Element)nl.item(0);
			textVal = el.getFirstChild().getNodeValue();
		}

		try {
			return Integer.getInteger(textVal);
		} catch (NumberFormatException numberFormatException) {
			return null;
		}
	}
	
	private static Date getDateValue(Node node, String tagName) {
		String textVal = null;
		Element ele = (Element) node;
		NodeList nl = ele.getElementsByTagName(tagName);
		if(nl != null && nl.getLength() > 0) {
			Element el = (Element)nl.item(0);
			textVal = el.getFirstChild().getNodeValue();
		}


		try {
			return Date.valueOf(textVal);
		} catch (Exception exception) {
			return null;
		}
	}
	
	private static Time getTimeValue(Node node, String tagName) {
		String textVal = null;
		Element ele = (Element) node;
		NodeList nl = ele.getElementsByTagName(tagName);
		if(nl != null && nl.getLength() > 0) {
			Element el = (Element)nl.item(0);
			textVal = el.getFirstChild().getNodeValue();
		}

		try {
			return Time.valueOf(textVal);
		} catch (Exception exception) {
			return null;
		}
	}
	
	public static class Death{
		private String player;
		private String killer;
		private String cause;
		private long time;
		
		public Death(){
			
		}
		
		public Death(String player, String killer, String cause, long time) {
			this.player = player;
			this.killer = killer;
			this.cause = cause;
			this.time = time;
		}

		public String getPlayer() {
			return player;
		}
		public void setPlayer(String player) {
			this.player = player;
		}
		public String getKiller() {
			return killer;
		}
		public void setKiller(String killer) {
			this.killer = killer;
		}
		public String getCause() {
			return cause;
		}
		public void setCause(String cause) {
			this.cause = cause;
		}
		public long getTime() {
			return time;
		}
		public void setTime(long time) {
			this.time = time;
		}
		
		
		
	}
	
}