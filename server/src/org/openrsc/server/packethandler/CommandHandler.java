package org.openrsc.server.packethandler;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Random;

import com.rscdaemon.scripting.Skill;
import com.runescape.entity.attribute.DropItemAttr;
import java.util.Iterator;

import org.apache.mina.common.IoSession;
import org.openrsc.server.Config;
import org.openrsc.server.ServerBootstrap;
import org.openrsc.server.entityhandling.EntityHandler;
import org.openrsc.server.entityhandling.defs.GameObjectDef;
import org.openrsc.server.entityhandling.defs.ItemDef;
import org.openrsc.server.event.ChangePasswordEvent;
import org.openrsc.server.event.DelayedEvent;
import org.openrsc.server.event.ShutdownEvent;
import org.openrsc.server.event.SingleEvent;
import org.openrsc.server.logging.Logger;
import org.openrsc.server.logging.model.GenericLog;
import org.openrsc.server.logging.model.GlobalLog;
import org.openrsc.server.model.ChatMessage;
import org.openrsc.server.model.GameObject;
import org.openrsc.server.model.InvItem;
import org.openrsc.server.model.Item;
import org.openrsc.server.model.Mob;
import org.openrsc.server.model.Npc;
import org.openrsc.server.model.Player;
import org.openrsc.server.model.Point;
import org.openrsc.server.model.World;
import org.openrsc.server.net.Packet;
import org.openrsc.server.states.CombatState;
import org.openrsc.server.util.DataConversions;
import org.openrsc.server.util.EntityList;
import org.openrsc.server.util.Formulae;

public class CommandHandler implements PacketHandler 
{
	public void handlePacket(Packet p, IoSession session) throws Exception 
	{
		Player player = (Player)session.getAttachment();
		if (player != null) 
		{
			if (System.currentTimeMillis() - player.getLastCommand() < 1000 && !player.isAdmin())
			{
				player.sendMessage(Config.PREFIX + "There's a second delay between using commands");
			}
			else 
			{
				String s = new String(p.getData()).trim();
				int firstSpace = s.indexOf(" ");
				String cmd = s;
				String[] args = new String[0];
				
				if (firstSpace != -1) 
				{
					cmd = s.substring(0, firstSpace).trim();
					args = s.substring(firstSpace + 1).trim().split(" ");
				} 
				try 
				{
					handleCommand(cmd.toLowerCase(), args, player);
				} 
				catch(Exception e) {}
			}
		}
	}
	
	public void handleCommand(String cmd, final String[] args, final Player player) 
	{
		for (int index = 0; index < args.length - 1; index++) 
		{
			args[index] = args[index].replace("-", " ");
			args[index] = args[index].replace("_", " ");
		}
		
		player.setLastCommand(System.currentTimeMillis());
		if ((cmd.equalsIgnoreCase("coords")) && (player.isMod() || player.isDev())) 
		{
            Player p = args.length > 0 ? 
                        World.getPlayer(DataConversions.usernameToHash(args[0])) :
                        player;
            
            if(p != null)
                player.sendMessage(Config.PREFIX + "is at X: " + player.getLocation().getX() + ", Y: " + player.getLocation().getY());
            else
                player.sendMessage(Config.PREFIX + "Invalid name");
		}
        else // Show online players
		if (cmd.equalsIgnoreCase("online") && player.isMod()) 
		{
			StringBuilder sb = new StringBuilder();
			synchronized (World.getPlayers()) 
			{
				EntityList<Player> players = World.getPlayers();
				sb.append("@gre@There are currently ").append(players.size()).append(" player(s) online.\n\n");
				for (Player p : players) 
				{
					Point loc = p.getLocation();
					if (player.isSub())
						sb.append("@whi@").append(p.getUsername()).append(loc.inWilderness() ? " @red@".concat("Wilderness").concat("\n") : "\n");
					else
					if (player.isMod())
						sb.append("@whi@").append(p.getUsername()).append(" @yel@(").append(loc).append(")").append(loc.inWilderness() ? " @red@".concat(loc.getDescription().concat("\n")) : "\n");	
				}
			}
			player.getActionSender().sendScrollableAlert(sb.toString());
		}
        else // toggle invisibility
		if (cmd.equalsIgnoreCase("invisible") && (player.isMod() || player.isDev())) 
		{
			player.invisible = !player.invisible;
			player.sendMessage(Config.PREFIX + "You are now " + (player.invisible ? "invisible" : "visible"));
			if (player.invisible)
			for (Player x : player.getViewArea().getPlayersInView())
			x.removeWatchedPlayer(player);
		} 
        else // leave CTF event
		if (cmd.equalsIgnoreCase("leavectf") && player.getLocation().inCtf())
		{
			player.removeFromCtf(player);
			player.sendAlert("You have been removed from CTF");
		}
        else // use global chat
		if (cmd.equalsIgnoreCase("say") || cmd.equalsIgnoreCase("s")) 
		{
			if (player.getPrivacySetting(4)) 
			{
				if (!World.global && !player.isMod())
					player.sendMessage(Config.PREFIX + "Global Chat is currently disabled");
				else 
				if (World.muted && !player.isMod())
					player.sendMessage(Config.PREFIX + "The world is muted");				
				else 
				if (player.getMuted() > 0)
					player.sendMessage(Config.PREFIX + "You are muted");		
				else
				if (System.currentTimeMillis() - player.getLastGlobal() < 10000 && !player.isMod())
					player.sendMessage(Config.PREFIX + "There's a 10 second delay using Global Chat");
				else 
				{	
					player.setLastGlobal(System.currentTimeMillis());
					String message = "";
					for (int i = 0; i < args.length; i++) 
					{
						message = message += args[i] + " ";
					}
						
					Logger.log(new GlobalLog(player.getUsernameHash(), player.getAccount(), player.getIP(), message, DataConversions.getTimeStamp()));
					synchronized (World.getPlayers()) 
					{
						for (Player p : World.getPlayers()) 
						{
							if (player.isAdmin())
								p.sendNotification("#adm#@yel@" + player.getUsername() + ":@whi@ " + message);
							else
							if (player.isMod())
								p.sendNotification("#mod#@whi@" + player.getUsername() + ":@whi@ " + message);
							else
							if (player.isDev())
								p.sendNotification("#dev#@red@" + player.getUsername() + ":@whi@ " + message);
							else
							if (player.isEvent())
								p.sendNotification("#eve#@eve@" + player.getUsername() + ":@whi@ " + message);
							else 
							if (!p.getIgnoreList().contains(player.getUsernameHash()) && p.getPrivacySetting(4) == true || player.isMod())
								p.sendGlobalMessage(player.getUsernameHash(), player.getGroupID() == 4 ? (player.isSub() ? 5 : 4) : player.getGroupID(), message);
						}						
					}
				}
			} 
			else
				player.sendMessage(Config.PREFIX + "You cannot use Global Chat as you have it disabled");
		} 
        else // Send an alert to a player
        if (cmd.equalsIgnoreCase("alert") && player.isMod()) 
        {
            String message = "";
            if (args.length > 0) 
            {
                Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
                if (p != null)
                {
                    for (int i = 1; i < args.length; i++)
                    message += args[i] + " ";
                    p.sendAlert((player.getStaffName()) + ":@whi@ " + message);
                    player.sendMessage(Config.PREFIX + "Alert sent");
                    Logger.log(new GenericLog(player.getUsername() + " alerted " + p.getUsername() +": " + message, DataConversions.getTimeStamp()));
                }
                else
                    player.sendMessage(Config.PREFIX + "Invalid player");
            } 
			else
                player.sendMessage(Config.PREFIX + "Syntax: " + cmd.toUpperCase() + " [name] [message]");	
		} 
		else
        if (cmd.equalsIgnoreCase("iplimit") && player.isAdmin())
        {
            if(args.length != 1)
            {
                player.sendMessage("Invalid Syntax - Usage: " + cmd.toUpperCase() + " [amount]");
                return;
            }
            try
            {
                Config.MAX_LOGINS_PER_IP = Integer.parseInt(args[0]);
                for(Player p : World.getPlayers())
                {
                    p.sendNotification(Config.PREFIX + "Max logins per IP has been set to: " + Config.MAX_LOGINS_PER_IP );
                }
            }
            catch(NumberFormatException e)
            {
                player.sendMessage("Invalid Syntax - Usage: iplimit [amount]");
                return;
            }
        }
        else // Give a player a skull
		if (cmd.equalsIgnoreCase("skull")) 
		{
			if (args.length > 0 && player.isAdmin() || player.isMod()) 
			{
				Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
				
				if (p != null)
				{
					p.addSkull(1200000);
				}
				else
				{
					player.sendMessage(Config.PREFIX + "Invalid name");	
				}
			} 
			else
			{
				player.addSkull(1200000);
			}
		} 
		else // Heal a player
		if (cmd.equalsIgnoreCase("heal") && player.isAdmin()) 
		{
            Player p = args.length > 0 ? 
                        World.getPlayer(DataConversions.usernameToHash(args[0])) :
                        player;
            
            if(p != null)
            {
                p.setCurStat(3, p.getMaxStat(3));
                p.sendStat(3);
            }
            else
                player.sendMessage(Config.PREFIX + "Invalid name");
		} 
		else // Toggle global chat
		if(cmd.equalsIgnoreCase("global") && player.isMod()) 
		{
			World.global = !World.global;
			synchronized (World.getPlayers()) 
			{
				for (Player p : World.getPlayers()) 
				{
					p.sendNotification(Config.PREFIX + "Global Chat has been " + (World.global ? "enabled" : "disabled") + " by " + player.getStaffName());
				}
			}
		} 
		else // Toggle if dueling is allowed
		if(cmd.equalsIgnoreCase("dueling") && player.isMod())
		{
			World.dueling = !World.dueling;
			synchronized (World.getPlayers()) 
			{
				for (Player p : World.getPlayers()) 
				{
					p.sendNotification(Config.PREFIX + "Dueling has been " + (World.dueling ? "enabled" : "disabled") + " by " + player.getStaffName());
				}
			}	
		} 
		else // Mute world
		if (cmd.equalsIgnoreCase("muted") && player.isAdmin()) 
		{
			World.muted = !World.muted;
			synchronized (World.getPlayers()) 
			{
				for (Player p : World.getPlayers()) 
				{
					p.sendNotification(Config.PREFIX + "World Mute has been " + (World.muted ? "enabled" : "disabled") + " by " + player.getStaffName());
				}
			}			
		} 
        else // Fatigue player
        if (cmd.equalsIgnoreCase("fatigue")) 
        {
            if (args.length > 0 && player.isMod()) 
            {
                Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
                if (p != null) 
                {
                    try
                    {
                        int fatigue = args.length > 1 ? Integer.parseInt(args[1]) : 100;
                        if(fatigue < 0)
                        {
                            fatigue = 0;
                        }
                        if(fatigue > 100)
                        {
                            fatigue = 100;
                        }
                        p.setFatigue((int)(18750 * (fatigue / 100.0D)));
                        p.sendFatigue();
                    }
                    catch(NumberFormatException e)
                    {
                        player.sendMessage("Invalid Syntax - Usage: ::" + cmd.toUpperCase() + " [player] [amount]");
                        return;
                    }
                    player.sendMessage(Config.PREFIX + p.getUsername() + "'s fatigue has been set to " + ((p.getFatigue() / 25) * 100 / 750) + "%");
                    Logger.log(new GenericLog(player.getUsername() + " set " + p.getUsername() + "'s fatigue to " + ((p.getFatigue() / 25) * 100 / 750) + "%", DataConversions.getTimeStamp()));
                } 
                else
                {
                    player.sendMessage(Config.PREFIX + "Invalid name");	
                }
            } 
            else 
            {
                player.setFatigue(18750);
                player.sendFatigue();
            }
        }
        else // Show a player's IP address
		if (cmd.equalsIgnoreCase("ip") && player.isAdmin()) 
		{
            Player p = args.length > 0 ? 
                        World.getPlayer(DataConversions.usernameToHash(args[0])) :
                        player;
            
            if(p != null)
            {
				long requestee = player.getUsernameHash();
				p.requestLocalhost(requestee);
				Logger.log(new GenericLog(player.getUsername() + " requested " + p.getUsername() + "'s IP", DataConversions.getTimeStamp()));
            }
            else
                player.sendMessage(Config.PREFIX + "Invalid name");
		} 
		else // Show info about a player
		if (cmd.equalsIgnoreCase("info") && player.isMod()) 
		{
            Player p = args.length > 0 ? 
                        World.getPlayer(DataConversions.usernameToHash(args[0])) :
                        player;
            
            if(p != null)
            {
				player.sendAlert(p.getUsername() + " (" + p.getStatus() + ") at " + player.getLocation().toString() + " (" + player.getLocation().getDescription() + ") % % Logged in: " + (DataConversions.getTimeStamp() - player.getLastLogin()) + " seconds % % Last moved: " + (int)((System.currentTimeMillis() - player.getLastMoved()) / 1000) + " % % Fatigue: " + ((p.getFatigue() / 25) * 100 / 750) + " % %Busy: " + (p.isBusy() ? "true" : "false"), true);

            }
            else
                player.sendMessage(Config.PREFIX + "Invalid name");
		} 
		else // Kick a player
		if (cmd.equalsIgnoreCase("kick") && player.isMod()) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}	
			
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
			if (p != null) 
			{
				if (p.isDueling() && !player.isAdmin())
					player.sendMessage(Config.PREFIX + "You cannot kick players who are dueling");	
				else 
				{				
					World.unregisterEntity(p);
					player.sendMessage(Config.PREFIX + p.getUsername() + " has been kicked");
					Logger.log(new GenericLog(player.getUsername() + " kicked " + p.getUsername(), DataConversions.getTimeStamp()));
				}
			}
		}
		else // Ban a player
		if (cmd.equalsIgnoreCase("ban") && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
			
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
			if (p != null)
			{
				p.ban();
			}
			else 
			{
				ServerBootstrap.getDatabaseService().submit(new Player.BanTransaction(DataConversions.usernameToHash(args[0]), true));
				Logger.log(new GenericLog(player.getUsername() + " banned " + DataConversions.hashToUsername(DataConversions.usernameToHash(args[0])), DataConversions.getTimeStamp()));
				player.sendMessage(Config.PREFIX + DataConversions.hashToUsername(DataConversions.usernameToHash(args[0])) + " has been banned");
			} 
		}
		else // Unban a player
		if (cmd.equalsIgnoreCase("unban") && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
			
			ServerBootstrap.getDatabaseService().submit(new Player.BanTransaction(DataConversions.usernameToHash(args[0]), false));			
			Logger.log(new GenericLog(player.getUsername() + " unbanned " + DataConversions.hashToUsername(DataConversions.usernameToHash(args[0])), DataConversions.getTimeStamp()));
			player.sendMessage(Config.PREFIX + DataConversions.hashToUsername(DataConversions.usernameToHash(args[0])) + " has been unbanned");				
		}
		else // Mute a player
		if (cmd.equalsIgnoreCase("mute") && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
			
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
			if (p != null) 
			{
				p.mute(0);										
				Logger.log(new GenericLog(player.getUsername() + " muted " + p.getUsername(), DataConversions.getTimeStamp()));
				ServerBootstrap.getDatabaseService().submit(new Player.MuteTransaction(DataConversions.usernameToHash(args[0]), true));
				player.sendMessage(Config.PREFIX + p.getUsername() + " has been muted");	
			}
		} 
		else // Unmute a player
		if (cmd.equalsIgnoreCase("unmute") && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
			
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
			if (p != null) 
			{
				p.unmute();										
				Logger.log(new GenericLog(player.getUsername() + " unmuted " + p.getUsername(), DataConversions.getTimeStamp()));
				ServerBootstrap.getDatabaseService().submit(new Player.MuteTransaction(DataConversions.usernameToHash(args[0]), false));
				player.sendMessage(Config.PREFIX + p.getUsername() + " has been unmuted");	
			}		
		}
		else // spawn/remove an NPC
		if (cmd.equalsIgnoreCase("npc") && (player.isAdmin() || player.isDev())) 
		{
			if (args.length == 0) 
			{
				for (Npc n : World.getZone(player.getX(), player.getY()).getNpcsAt(player.getX(), player.getY())) 
				{
					Mob opponent = n.getOpponent();
					
					if (opponent != null)
					{
						opponent.resetCombat(CombatState.ERROR);
					}
					
					n.resetCombat(CombatState.ERROR);
					World.unregisterEntity(n);
					n.remove();
				}
			} 
			else 
			{
				int id = -1;
				try 
				{
					id = Integer.parseInt(args[0]);
				} 
				catch(NumberFormatException ex)
                {
                    player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [npc_id] [duration]");
                    return;
                }
				int duration = 0;
				if (args.length == 1)
				{
					duration = 60000;
				} 
				else 
				if (args.length == 2) 
				{
					try 
					{
						duration = Integer.parseInt(args[1]) * 60000;
					} 
                    catch(NumberFormatException ex)
                    {
                        player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [npc_id] [duration]");
                        return;
                    }
                }
				if (EntityHandler.getNpcDef(id) != null) 
				{
					final Npc n = new Npc(id, player.getX(), player.getY(), player.getX() - 2, player.getX() + 2, player.getY() - 2, player.getY() + 2);
					n.setRespawn(false);
					World.registerEntity(n);
					World.getDelayedEventHandler().add(new SingleEvent(null, duration) 
					{
						public void action() 
						{
							Mob opponent = n.getOpponent();
							
							if (opponent != null)
							{
								opponent.resetCombat(CombatState.ERROR);
							}
							
							n.resetCombat(CombatState.ERROR);
							n.remove();
						}
					});
				} 
				else
				{
					player.sendMessage(Config.PREFIX + "Invalid ID");
				}
			}
		} 
		else // Teleport
		if ((cmd.equalsIgnoreCase("teleport") || cmd.equalsIgnoreCase("tp")) && (player.isMod() || player.isDev() || player.isEvent())) 
		{
			player.resetLevers();
			if (args.length == 0) 
			{
				player.teleport = !player.teleport;
				player.sendMessage(Config.PREFIX + "Single click teleport " + (player.teleport ? "enabled" : "disabled"));
			} 
			else 
			if (args.length == 1) 
			{
				if(!EntityHandler.getTeleportManager().containsTeleport(args[0]))
				{
					player.sendMessage(Config.PREFIX + "Teleport location \"" + args[0] + "\" does not exist");
					player.sendMessage(Config.PREFIX + "hint: you can add it via the website");
				}
				else
				{
					player.teleport(EntityHandler.getTeleportManager().getTeleport(args[0]), false);
				}
			} 
			else if (args.length == 2) 
			{
				if (World.withinWorld(Integer.parseInt(args[0]), Integer.parseInt(args[1])))
				{
					player.teleport(Integer.parseInt(args[0]), Integer.parseInt(args[1]), false);
				}
            }
			else if (args.length == 3) 
			{
				if (World.withinWorld(Integer.parseInt(args[0]), Integer.parseInt(args[1])))
				{
                    try
                    {
                        player.teleport(Integer.parseInt(args[0]), Integer.parseInt(args[1]), false);
                    }
                    catch(NumberFormatException e)
                    {
                        player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [x] [y]");
                        return;
                    }
				}
			}	
		} 
        else // Show appearance change screen
        if((cmd.equalsIgnoreCase("appearance")) && (player.isAdmin()))
        {
            Player p = args.length > 0 ? 
                        World.getPlayer(DataConversions.usernameToHash(args[0])) :
                        player;
            
            if(p != null)
            {
                p.setChangingAppearance(true);
                p.getActionSender().sendAppearanceScreen();
            }
            else
                player.sendMessage(Config.PREFIX + "Invalid name");
		}
        else // Summon a player
		if (cmd.equalsIgnoreCase("summon") && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
			
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
			if (p != null) 
			{
                if(p.getGroupID() >= 4)
                {
                    if (p.getLocation().inCtf())
                        player.sendMessage(Config.PREFIX + "You cannot summon players who are in CTF");
                    else
                    if (p.isDueling() && !player.isAdmin())
                        player.sendMessage(Config.PREFIX + "You cannot summon players who are dueling");
                    else 
                    if (player.getLocation().inWilderness() && !player.isAdmin())
                        player.sendMessage(Config.PREFIX + "You cannot summon players into the wilderness");
                    else 
                    {
                        p.setReturnPoint();
                        p.teleport(player.getX(), player.getY(), false);
                        Logger.log(new GenericLog(player.getUsername() + " summoned " + p.getUsername() + " to " + "(" + p.getX() + ", " + p.getY() + ")", DataConversions.getTimeStamp()));					
                    }
                }
                else
                {
                    player.sendMessage(Config.PREFIX + "Staff members can not be summoned");
                }
			} 
			else
			{
				player.sendMessage(Config.PREFIX + "Invalid name");
			}
		} 
		else // Return a player to where they were before summoning
		if (cmd.equalsIgnoreCase("return") && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
			
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
			if (p != null) 
			{
                if(p.getGroupID() >= 4)
                {
                    if (p.wasSummoned()) 
                    {
                        p.setSummoned(false);
                        p.teleport(p.getReturnX(), p.getReturnY(), false);
                        Logger.log(new GenericLog(player.getUsername() + " returned " + p.getUsername() + " to " + " (" + p.getX() + ", " + p.getY() + ")", DataConversions.getTimeStamp()));
                    } 
                    else
                    {
                        player.sendMessage(Config.PREFIX + p.getUsername() + " has no return point set");
                    }
                }
                else
                {
                    player.sendMessage(Config.PREFIX + "Staff members can not be summoned");
                }
			} 
			else
			{
				player.sendMessage(Config.PREFIX + "Invalid name");
			}
		} 
		else // Jail a player
		if (cmd.equalsIgnoreCase("jail") && (player.isMod() || player.isDev() || player.isEvent())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
            
            Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));

			if (p != null) 
			{
                if (p.getGroupID() >= 4) 
                {
                    if(!p.getLocation().isInJail())
                    {
                        p.teleport(793, 24, false);
                        player.sendMessage(Config.PREFIX + p.getUsername() + " has been jailed");
                        p.sendAlert("You have been jailed.");
                    }
                    else
                    {
                        player.sendMessage(Config.PREFIX + p.getUsername() + " is already in jail");
                    }
                } 
                else
                {
                    player.sendMessage(Config.PREFIX + "Staff members can not be jailed");
                }
            }
			else
			{
				player.sendMessage(Config.PREFIX + "Invalid name");
			}
		} 
		else // Release a player from jail
		if (cmd.equalsIgnoreCase("release") && (player.isMod() || player.isDev() || player.isEvent())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
            
            Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
            
			if (p != null) 
			{
                if (p.getGroupID() >= 4) 
                {
                    if(p.getLocation().isInJail())
                    {
                        p.teleport(120, 648, false);
                        p.sendAlert("You have been released from jail.");
                        player.sendMessage(Config.PREFIX + p.getUsername() + " has been released from jail.");
                    }
                    else
                    {
                        player.sendMessage(Config.PREFIX + p.getUsername() + " is not in jail");
                    }
                } 
                else
                {
                    player.sendMessage(Config.PREFIX + "Staff members can not be released");
                }
            }
			else
			{
				player.sendMessage(Config.PREFIX + "Invalid name");
			}
		} 
		else // Go to a player's location
		if ((cmd.equalsIgnoreCase("goto") || cmd.equalsIgnoreCase("tpto") || cmd.equalsIgnoreCase("teleportto")) && (player.isMod() || player.isDev())) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [name]");
				return;
			}
            
			Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
			
            if(p != null)
            {
                player.teleport(p.getX(), p.getY(), false);
                Logger.log(new GenericLog(player.getUsername() + " went to " + p.getUsername() + " (" + p.getX() + ", " + p.getY() + ")", DataConversions.getTimeStamp()));
            }
 			else
			{
				player.sendMessage(Config.PREFIX + "Invalid name");
			}
		} 
		else // Restart server
		if ((cmd.equalsIgnoreCase("restart") || cmd.equalsIgnoreCase("update")) && (player.isAdmin() || player.isDev()))
		{
			String message = "";
			if (args.length > 0) {
				for (String s : args)
					message += (s + " ");
				message = message.substring(0, message.length() - 1);
			}
			World.getWorld().getEventPump().submit(new ShutdownEvent(true, message));
		}
		else // spawn an item
		if (cmd.equalsIgnoreCase("item") && player.isAdmin())
		{
			if (args.length < 1 || args.length > 2)
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [id] [amount]");
			}
			else 
			{
                try
                {
                    int id = Integer.parseInt(args[0]);
                    ItemDef itemDef = EntityHandler.getItemDef(id);
                    if (EntityHandler.getItemDef(id) != null) 
                    {
                        long amount = 1;
                        if (args.length == 2)
                            amount = Long.parseLong(args[1]);

                        if(itemDef.isStackable())
                        {
                            InvItem invItem = new InvItem(id, amount);
                            player.getInventory().add(invItem);
                        }
                        else
                        {
                            for(int i = 0; i < amount; i++)
                            {
                                InvItem invItem = new InvItem(id, amount);
                                player.getInventory().add(invItem);
                            }
                        }
                        player.sendInventory();
                        Logger.log(new GenericLog(player.getUsername() + " spawned " + amount + " " + EntityHandler.getItemDef(id).name, DataConversions.getTimeStamp()));
                    } 
                    else
                    {
                        player.sendMessage(Config.PREFIX + "Invalid ID");
                    }
                }
                catch (NumberFormatException e)
                {
                    player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " [id] [amount]");
                    return;
                }
			}
		}
        else // Spawn or remove an object
		if (cmd.equalsIgnoreCase("object") && (player.isAdmin() || player.isDev()))
		{
            if(args.length == 0)
            {
                player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " create [object_id] [direction]" + (player.isAdmin() ? " [from_database] eg. '::object create 1 0 true'" : "") + " OR");
                player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " delete" + (player.isAdmin() ? " [from_database] eg. '::object delete true'" : ""));
                return;
            }
            
            if(args[0].equalsIgnoreCase("create"))
            {
                if(args.length <= 1)
                {
                    player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " create [object_id] [direction]" + (player.isAdmin() ? " [from_database] eg. '::object create 1 true'" : "") + " OR");
                    return;
                }
                try
                {
                    int object_id           = Integer.parseInt(args[1]);
                    GameObjectDef objectDef = EntityHandler.getGameObjectDef(object_id);
                    int direction           = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
                    
                    if(objectDef == null)
                    {
						player.sendMessage(Config.PREFIX + "Invalid ID");
                        return;
                    }
                    
                    World.registerEntity(new GameObject(player.getLocation(), object_id, direction, 0));
                    player.sendMessage(Config.PREFIX + "Created " + objectDef.getName());
                    
                    if(player.isAdmin() && args.length >= 4)
                    {
                        boolean sql = Boolean.parseBoolean(args[3]);
                        if(sql)
                        {
							try {
								World.getWorldLoader().writeQuery("INSERT INTO `spawn_object` (`object`, `x`, `y`, `direction`) VALUES ('" + object_id + "', '" + player.getX() + "', '" + player.getY() + "', '" + direction + "')");
                                player.sendMessage(Config.PREFIX + "Object added to database");
							} catch (SQLException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
                        }
                    }
                }
                catch(NumberFormatException e)
                {
                    player.sendMessage(Config.PREFIX + "Invalid args. Syntax: " + cmd.toUpperCase() + " create [object_id] [direction]" + (player.isAdmin() ? " [from_database] eg. '::object create 1 true'" : "") + " OR");
                    return;
                }
            }
            else
            if(args[0].equalsIgnoreCase("delete"))
            {
               GameObject o = World.getZone(player.getX(), player.getY()).getObjectAt(player.getX(), player.getY());
               if(o == null)
               {
                   player.sendMessage(Config.PREFIX + "There is no object at your current location.");
               }
               else
               {
                   World.unregisterEntity(o);
                   player.sendMessage(Config.PREFIX + "Removed " + o.getGameObjectDef().getName());
               }
               
                if(player.isAdmin() && args.length >= 2)
                {
                    boolean sql = Boolean.parseBoolean(args[1]);

                    if(sql)
                    {
                        try {
                            World.getWorldLoader().writeQuery("DELETE FROM `spawn_object` WHERE `x` = '" + player.getX() + "' AND `y` = '" + player.getY() + "'");
                            player.sendMessage(Config.PREFIX + "Object removed from database");
                        } catch (SQLException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    }
                }
            }
		} 

		else
		if (cmd.equalsIgnoreCase("wipeinventory") && player.isAdmin()) 
		{
			for (InvItem i : player.getInventory().getItems()) {
				if (player.getInventory().get(i).isWielded()) {
					player.getInventory().get(i).setWield(false);
					player.updateWornItems(i.getWieldableDef().getWieldPos(), player.getPlayerAppearance().getSprite(i.getWieldableDef().getWieldPos()));
				}	
			}
			player.getInventory().getItems().clear();
			player.sendInventory();
		} else if (cmd.equalsIgnoreCase("wipebank") && player.isMod())
			player.getBank().getItems().clear();			
		else if (cmd.equalsIgnoreCase("kill") && player.isAdmin()) {
			if (args.length != 1)
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: KILL [user]");
			else {
				Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
				if (p != null) {
					p.setLastDamage(99);
					p.setHits(p.getHits() - 99);
					ArrayList<Player> playersToInform = new ArrayList<Player>();
					playersToInform.addAll(player.getViewArea().getPlayersInView());
					playersToInform.addAll(p.getViewArea().getPlayersInView());
					for (Player i : playersToInform)
						i.informOfModifiedHits(p);
					p.sendStat(3);
					if (p.getHits() <= 0)
						p.killedBy(player, false);						
				}
			}
		} else if (cmd.equalsIgnoreCase("damage") && player.isAdmin()) {
			if (args.length != 2)
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: DAMAGE [user] [amount]");
			else {
				int damage = Integer.parseInt(args[1]);
				Player p = World.getPlayer(DataConversions.usernameToHash(args[0]));
				if (p != null) {
					p.setLastDamage(damage);
					p.setHits(p.getHits() - damage);
					ArrayList<Player> playersToInform = new ArrayList<Player>();
					playersToInform.addAll(player.getViewArea().getPlayersInView());
					playersToInform.addAll(p.getViewArea().getPlayersInView());
					for(Player i : playersToInform)
						i.informOfModifiedHits(p);
					p.sendStat(3);
					if (p.getHits() <= 0)
						p.killedBy(player, false);	
				}
			}
		} else if (cmd.equalsIgnoreCase("stats") && player.isAdmin()) {
			int level = 99;
			if (args.length > 0) {
				try {
					level = Integer.parseInt(args[0]);
				} catch (Exception e) { }
			}
			
			if (level > 255) {
				level = 255;
			}
			if (level < 1) {
				level = 1;
			}
			
			for (Skill skill : Skill.values()) {
				player.setCurStat(skill.ordinal(), level);
				player.setMaxStat(skill.ordinal(), level);
				player.setExp(skill.ordinal(), Formulae.lvlToXp(level));
			}
			player.setCombatLevel(Formulae.getCombatlevel(player.getMaxStats()));
			player.sendStats();
			player.sendMessage(Config.PREFIX + "Set all stats to level " + level + ".");
		} else if (cmd.equalsIgnoreCase("summonall") && player.isAdmin()){
			if (args.length == 0) {
				synchronized (World.getPlayers()) {
					for (Player p : World.getPlayers()) {
						p.setReturnPoint();
						p.resetLevers();
						p.teleport(player.getX(), player.getY(), true);
					}
				}
			} else if (args.length == 2) {
				int width = -1;
				int height = -1;
				try {
					width = Integer.parseInt(args[0]);
					height = Integer.parseInt(args[1]);
				} catch(Exception ex) {
					player.sendMessage(Config.PREFIX + "Invalid dimensions");
				}
				if (width > 0 && height > 0) {
					Random rand = new Random(System.currentTimeMillis());
					synchronized (World.getPlayers()) {
						for (Player p : World.getPlayers()) {
							if (p != player) {
								int x = rand.nextInt(width);
								int y = rand.nextInt(height);
								boolean XModifier = (rand.nextInt(2) == 0 ? false : true);
								boolean YModifier = (rand.nextInt(2) == 0 ? false : true);
								if (XModifier)
									x = -x;
								if (YModifier)
									y = -y;
								p.setReturnPoint();
								p.resetLevers();
								p.teleport(player.getX() + x, player.getY() + y, false);
							}
						}
					}
				}
			}	
		} else if(cmd.equalsIgnoreCase("returnall") && player.isAdmin()) {
			synchronized (World.getPlayers()) {
				for (Player p : World.getPlayers()) {
					if (p != null) {
						if (p.wasSummoned()) {
							p.setSummoned(false);
							p.teleport(p.getReturnX(), p.getReturnY(), false);
						}
					}
				}
			}
		} else if (cmd.equalsIgnoreCase("massitem") && player.isAdmin()) {
			if (args.length != 2) {
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: MASSITEM [id] [amount]");
				return;
			}
			int id = Integer.parseInt(args[0]);
			int amount = Integer.parseInt(args[1]);
			if (EntityHandler.getItemDef(id) != null) {
				int x = 0;
				int y = 0;
				int baseX = player.getX();
				int baseY = player.getY();
				int nextX = 0;
				int nextY = 0;
				int dX = 0;
				int dY = 0;
				int minX = 0;
				int minY = 0;
				int maxX = 0;
				int maxY = 0;
				int scanned = 0;
				while (scanned < amount) {
					scanned++;
					if (dX < 0) {
						x -= 1;
						if (x == minX) {
							dX = 0;
							dY = nextY;
							if (dY < 0)
								minY -= 1;
							else
								maxY += 1;
							nextX = 1;
						}
					} else if (dX > 0) {
						x += 1;
						if (x == maxX) {
							dX = 0;
							dY = nextY;
							if (dY < 0)
								minY -=1;
							else
								maxY += 1;
							nextX = -1;
						}
					} else {
						if (dY < 0) {
							y -= 1;
							if (y == minY) {
								dY = 0;
								dX = nextX;
								if (dX < 0)
									minX -= 1;
								else
									maxX += 1;
								nextY = 1;
							}
						} else if (dY > 0) {
							y += 1;
							if (y == maxY) {
								dY = 0;
								dX = nextX;
								if (dX < 0)
									minX -= 1;
								else
									maxX += 1;
								nextY = -1;
							}
						} else {
							minY -= 1;
							dY = -1;
							nextX = 1;
						}
					}
					if (!((baseX + x) < 0 || (baseY + y) < 0 || ((baseX + x) >= World.MAX_WIDTH) || ((baseY + y) >= World.MAX_HEIGHT))) {
						if ((World.mapValues[baseX + x][baseY + y] & 64) == 0)
							World.registerEntity(new Item(id, baseX + x, baseY + y, amount, (Player[])null));
					}
				}
			}
		} else if (cmd.equalsIgnoreCase("npcevent") && player.isAdmin()) {
			if (args.length < 1) {
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: npcevent [npc_id] [npc_amount] [item_id] [item_amount]");
				return;
			}
			int npcID, npcAmt = 0, random = 0;
			InvItem item = null;
			try {
				npcID = Integer.parseInt(args[0]);
				npcAmt = Integer.parseInt(args[1]);
				int id = Integer.parseInt(args[2]);
				int amount = args.length > 2 ? Integer.parseInt(args[3]) : 1;
				item = new InvItem(id, amount);
				random = DataConversions.random(0, npcAmt);
			} catch (Exception e) {
				player.sendMessage(Config.PREFIX + "Error parsing command.");
				return;
			}
			int x = 0;
			int y = 0;
			int baseX = player.getX();
			int baseY = player.getY();
			int nextX = 0;
			int nextY = 0;
			int dX = 0;
			int dY = 0;
			int minX = 0;
			int minY = 0;
			int maxX = 0;
			int maxY = 0;
			int scanned = -1;
			while (scanned < npcAmt) {
				scanned++;
				if (dX < 0) {
					x -= 1;
					if (x == minX) {
						dX = 0;
						dY = nextY;
						if (dY < 0)
							minY -= 1;
						else
							maxY += 1;
						nextX = 1;
					}
				} else if (dX > 0) {
					x += 1;
					if (x == maxX) {
						dX = 0;
						dY = nextY;
						if (dY < 0)
							minY -=1;
						else
							maxY += 1;
						nextX = -1;
					}
				} else {
					if (dY < 0) {
						y -= 1;
						if (y == minY) {
							dY = 0;
							dX = nextX;
							if (dX < 0)
								minX -= 1;
							else
								maxX += 1;
							nextY = 1;
						}
					} else if (dY > 0) {
						y += 1;
						if (y == maxY) {
							dY = 0;
							dX = nextX;
							if (dX < 0)
								minX -= 1;
							else
								maxX += 1;
							nextY = -1;
						}
					} else {
						minY -= 1;
						dY = -1;
						nextX = 1;
					}
				}
				if (!((baseX + x) < 0 || (baseY + y) < 0 || ((baseX + x) >= World.MAX_WIDTH) || ((baseY + y) >= World.MAX_HEIGHT))) {
					if ((World.mapValues[baseX + x][baseY + y] & 64) == 0) {
						final Npc n = new Npc(npcID, baseX + x, baseY + y, baseX + x - 20, baseX + x + 20, baseY + y - 20, baseY + y + 20);
						
						if (scanned == random) {
							DropItemAttr attr = new DropItemAttr(n, item);
							n.addAttr(attr);
						}
						
						n.setRespawn(false);
						World.registerEntity(n);
						World.getDelayedEventHandler().add(new SingleEvent(null, 120000 /* 2 minutes */) {
							public void action() {
								Mob opponent = n.getOpponent();
								if (opponent != null)
									opponent.resetCombat(CombatState.ERROR);
								n.resetCombat(CombatState.ERROR);
								n.remove();
							}
						});
					}
				}
			}
		} else if (cmd.equalsIgnoreCase("massnpc") && player.isAdmin()) {
			if (args.length != 1) {
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: NPC [id]");
				return;
			}
			int id = Integer.parseInt(args[0]);
			if (EntityHandler.getNpcDef(id) != null) {
				int x = 0;
				int y = 0;
				int baseX = player.getX();
				int baseY = player.getY();
				int nextX = 0;
				int nextY = 0;
				int dX = 0;
				int dY = 0;
				int minX = 0;
				int minY = 0;
				int maxX = 0;
				int maxY = 0;
				int scanned = 0;
				while (scanned < 400) {
					scanned++;
					if (dX < 0) {
						x -= 1;
						if (x == minX) {
							dX = 0;
							dY = nextY;
							if (dY < 0)
								minY -= 1;
							else
								maxY += 1;
							nextX = 1;
						}
					} else if (dX > 0) {
						x += 1;
						if (x == maxX) {
							dX = 0;
							dY = nextY;
							if (dY < 0)
								minY -=1;
							else
								maxY += 1;
							nextX = -1;
						}
					} else {
						if (dY < 0) {
							y -= 1;
							if (y == minY) {
								dY = 0;
								dX = nextX;
								if (dX < 0)
									minX -= 1;
								else
									maxX += 1;
								nextY = 1;
							}
						} else if (dY > 0) {
							y += 1;
							if (y == maxY) {
								dY = 0;
								dX = nextX;
								if (dX < 0)
									minX -= 1;
								else
									maxX += 1;
								nextY = -1;
							}
						} else {
							minY -= 1;
							dY = -1;
							nextX = 1;
						}
					}
					if (!((baseX + x) < 0 || (baseY + y) < 0 || ((baseX + x) >= World.MAX_WIDTH) || ((baseY + y) >= World.MAX_HEIGHT))) {
						if ((World.mapValues[baseX + x][baseY + y] & 64) == 0) {
							final Npc n = new Npc(id, baseX + x, baseY + y, baseX + x - 20, baseX + x + 20, baseY + y - 20, baseY + y + 20);
							n.setRespawn(false);
							World.registerEntity(n);
							World.getDelayedEventHandler().add(new SingleEvent(null, 60000) {
								public void action() {
									Mob opponent = n.getOpponent();
									if (opponent != null)
										opponent.resetCombat(CombatState.ERROR);
									n.resetCombat(CombatState.ERROR);
									n.remove();
								}
							});
						}
					}
				}
			}
		}  
		else if (cmd.equalsIgnoreCase("playertalk") && player.isAdmin()) {
			if (args.length < 2) {
				player.sendMessage(Config.PREFIX + "Invalid syntax. ::PLAYERTALK [player] [msg]");
				return;
			}
			String msg = "";
			for (int i = 1; i < args.length; i++) {
				msg += args[i] + " ";
			}
			Player pl = World.getPlayer(DataConversions.usernameToHash(args[0]));
			if (pl == null) {
				player.sendMessage(Config.PREFIX + "Invalid Player");
				return;
			}
			pl.addMessageToChatQueue(msg);
		} else if (cmd.equalsIgnoreCase("npctalk") && player.isAdmin()) {
			String newStr = "";
			for (int i = 1; i < args.length; i++)
				newStr = newStr += args[i] + " ";
					
			final Npc n = World.getNpc(Integer.parseInt(args[0]), player.getX() - 10, player.getX() + 10, player.getY() - 10, player.getY() + 10);
			
			if (n != null) {
				for (Player p : player.getViewArea().getPlayersInView())
					p.informOfNpcMessage(new ChatMessage(n, newStr, p));		
			} else
				player.sendMessage(Config.PREFIX + "Invalid NPC");
		} if (cmd.equalsIgnoreCase("stat") && player.isAdmin()) {
			if (args.length < 2 || args.length > 3)
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: STAT [stat] [level] [user]");
			else {
				byte stat = -1;
				if ((stat = (byte)statArray.indexOf(args[0])) != -1) {
					int level = Integer.parseInt(args[1]);
					if(level < 100 && level >= 1) {
						Player playerToEdit = player;
						if(args.length == 3) {
							playerToEdit = World.getPlayer(DataConversions.usernameToHash(args[2]));
						}
						if(playerToEdit != null) {
							if (level == 1) {
								playerToEdit.setExp(stat, 0);
								playerToEdit.setCurStat(stat, 1);
								playerToEdit.setMaxStat(stat, 1);
							} else {
								playerToEdit.setExp(stat, Formulae.experienceArray[level - 2]);
								playerToEdit.setCurStat(stat, level);
								playerToEdit.setMaxStat(stat, Formulae.experienceToLevel((int) playerToEdit.getExp(stat)));
							}
							playerToEdit.setCombatLevel(Formulae.getCombatlevel(playerToEdit.getMaxStats()));
							playerToEdit.sendStats();
							if (playerToEdit == player)
								player.sendMessage(Config.PREFIX + "You set your " + statArray.get(stat) + " to " + level);
							else {
								player.sendMessage(Config.PREFIX + "Successfully edited " + playerToEdit.getUsername() + "'s " + statArray.get(stat) + " to " + level);
								playerToEdit.sendMessage(Config.PREFIX + player.getUsername() + " has set your " + statArray.get(stat) + " to " + level);
							}
						} else
							player.sendMessage(Config.PREFIX + "Invalid name");
					} else
						player.sendMessage(Config.PREFIX + "Invalid level");
				} else
					player.sendMessage(Config.PREFIX + "Invalid stat");
			}
		} else if (cmd.equalsIgnoreCase("smitenpc") && (player.isAdmin() || player.isDev())) {
			if (args.length == 2) {
				try {
					int id = Integer.parseInt(args[0]);
					Npc n = World.getNpc(id, player.getX() - 10, player.getX() + 10, player.getY() - 10, player.getY() + 10);
					if (n != null) {
						try {
							int damage = Integer.parseInt(args[1]);
							n.setLastDamage(damage);
							n.setHits(n.getHits() - damage);
							for (Player p : n.getViewArea().getPlayersInView())
								p.informOfModifiedHits(n);
							GameObject sara = new GameObject(n.getLocation(), 1031, 0, 0);
							World.registerEntity(sara);
							World.delayedRemoveObject(sara, 600);
							if (n.getHits() < 1)
								n.killedBy(player);
						} catch(Exception ex) {}
					}
				} catch(Exception e) {}
			} else
				player.sendMessage(Config.PREFIX + "Invalid args: SMITENPC [ID] [DAMAGE]");
		} else if (cmd.equalsIgnoreCase("refreshchests") && player.isAdmin()) {
			try {
				EntityHandler.setChestDefinitions(World.getWorldLoader().loadChestDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshstalls") && player.isAdmin()) {
			try {
				EntityHandler.setStallThievingDefinitions(World.getWorldLoader().loadStallThievingDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshlockeddoors") && player.isAdmin()) {
			try {
				EntityHandler.setPicklockDoorDefinitions(World.getWorldLoader().loadPicklockDoorDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshpickpocket") && player.isAdmin()) {
			try {
				EntityHandler.setPickPocketDefinitions(World.getWorldLoader().loadPickPocketDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshwoodcut") && player.isAdmin()) {
			try {
				EntityHandler.setWoodcutDefinitions(World.getWorldLoader().loadWoodcuttingDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshfishing") && player.isAdmin()) {
			try {
				EntityHandler.setFishingDefinitions(World.getWorldLoader().loadFishingDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if(cmd.equalsIgnoreCase("refreshnpchandlers") && player.isAdmin()) {
			try {
				World.getWorldLoader().loadNpcHandlers();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshagility") && player.isAdmin()) {
			try {
				EntityHandler.setAgilityCourseDefinitions(World.getWorldLoader().loadAgilityCourseDefinitions());
				EntityHandler.setAgilityDefinitions(World.getWorldLoader().loadAgilityDefinitons());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshedibles") && player.isAdmin()) {
			try {
				EntityHandler.setItemHealingDefinitions(World.getWorldLoader().loadItemEdibleHeals());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshnpcs") && player.isAdmin()) {
			for (Npc n : World.getNpcs())
				n.unconditionalRemove();
			try {
				World.getWorldLoader().loadNpcLocations();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshcerters") && player.isAdmin()) {
			try {
				EntityHandler.setCerterDefinitions(World.getWorldLoader().loadCerterDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshherbs") && player.isAdmin()) {
			try {
				EntityHandler.setHerbDefinitions(World.getWorldLoader().loadHerbDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("refreshunidentifiedherbs") && player.isAdmin()) {
			try {
				EntityHandler.setUnidentifiedHerbDefinitions(World.getWorldLoader().loadUnidentifiedHerbDefinitions());
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else if (cmd.equalsIgnoreCase("event")) {
			if (args.length > 1) {
				if (player.isMod() || player.isEvent()) {
					if (!World.eventRunning) {
						try {
							int low = Integer.parseInt(args[0]);
							int high = Integer.parseInt(args[1]);
							if (low <= high && low >= 3 && high <= 123) {
								World.eventLow = low;
								World.eventHigh = high;
								World.setEvent(player.getX(), player.getY());
								synchronized (World.getPlayers()) {
									for (Player p : World.getPlayers()) {
										p.sendNotification(Config.PREFIX + "Type @gre@::EVENT@whi@ to join the event!");
										if (player.getLocation().inWilderness())
											p.sendNotification(Config.PREFIX + "@red@Warning:@whi@ This event is located in the wilderness!");									
										p.sendNotification(Config.PREFIX + "@yel@Level Restriction:@whi@ Level " + low + (low != high ? " to Level " + high : ""));
										p.sendNotification(Config.PREFIX + "An event has been set by " + player.getStaffName());
									}
								}
							} else
								player.sendMessage(Config.PREFIX + "Invalid level range");
						} catch(Exception e) {
							player.sendMessage(Config.PREFIX + "Invalid level range");
						}							
					} else
						player.sendMessage(Config.PREFIX + "There is already an event running!");
				} else
					player.sendMessage(Config.PREFIX + "Invalid args! Syntax EVENT");
			} else {
				if (World.eventPoint != null) {
					if (!player.getLocation().inWilderness() && !player.isTrading() && !player.isBusy() && !player.accessingShop() && !player.accessingBank() && !player.isDueling()) {
						if(player.getLocation().inBounds(792, 23, 794, 25))
						{
							player.sendMessage(Config.PREFIX + "You cannot use ::event whilst being jailed.");
							return;
						}
						if (!World.joinEvent(player))
							player.sendMessage(Config.PREFIX + "You aren't eligible for this event");
					} else
						player.sendMessage(Config.PREFIX + "You cannot enroll in this event right now");
				}
			}
		} else 
			if (cmd.equalsIgnoreCase("endevent") && (player.isMod() || player.isDev() || player.isEvent())) 
			{
			if (World.eventRunning) {
				World.setEvent(-1, -1);
				synchronized (World.getPlayers()) {
					for (Player p : World.getPlayers())
						p.sendNotification(Config.PREFIX + "Event registration has been closed by " + player.getStaffName());	
				}
			} else 
				player.sendMessage(Config.PREFIX + "No event is currently running");
			} else if (cmd.equalsIgnoreCase("islandsafe") && (player.isEvent() || player.isMod())) {
				World.islandSafe = !World.islandSafe;
				player.sendMessage(Config.PREFIX + "Safe mode " + (World.islandSafe ? "enabled" : "disabled"));
			} else if (cmd.equalsIgnoreCase("islandcombat") && (player.isEvent() || player.isMod())) {
				World.islandCombat = !World.islandCombat;
				player.sendMessage(Config.PREFIX + "Combat " + (World.islandCombat ? "disabled" : "enabled"));
			} else if(cmd.equalsIgnoreCase("ipban") && player.isAdmin()) {
			if (args.length != 1) {
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: IPBAN [ip]");
				return;
			}
				new Thread(
					new Runnable()
					{
						@Override
						public final void run()
						{
							try {
								Runtime.getRuntime().exec("IPTABLES -A INPUT -s " + args[0] + " -j DROP");
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				).start();
				
				player.sendMessage(Config.PREFIX + args[0] + " was successfully IP banned");
		} else if (cmd.equalsIgnoreCase("unipban") && player.isAdmin()) {
			if (args.length != 1) {
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: UNIPBAN [ip]");
				return;
			}
				new Thread(
						new Runnable()
						{
							@Override
							public final void run()
							{
								try {
									Runtime.getRuntime().exec("IPTABLES -D INPUT -s " + args[0] + " -j ACCEPT");
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						}
					).start();	
				player.sendMessage(Config.PREFIX + args[0] + " has been removed from the IP ban list");
		} else if (cmd.equalsIgnoreCase("time") || cmd.equalsIgnoreCase("date")) {
			player.sendMessage(Config.PREFIX + Config.SERVER_NAME + "'s time/date is:@gre@ " + new java.util.Date().toString());
		} else if (cmd.equalsIgnoreCase("skiptutorial")) {
			if (player.getLocation().onTutorialIsland())
			{
				for (InvItem i : player.getInventory().getItems()) {
					if (player.getInventory().get(i).isWielded()) {
						player.getInventory().get(i).setWield(false);
						player.updateWornItems(i.getWieldableDef().getWieldPos(), player.getPlayerAppearance().getSprite(i.getWieldableDef().getWieldPos()));
					}	
				}
				player.getInventory().getItems().clear();
				player.sendInventory();			
				player.getInventory().add(new InvItem(70, 1));
				player.getInventory().add(new InvItem(1263, 1));
				player.getInventory().add(new InvItem(156, 1));
				player.getInventory().add(new InvItem(4, 1));
				player.getInventory().add(new InvItem(87, 1));
				player.getInventory().add(new InvItem(376, 1));
				player.sendInventory();
				player.teleport(122, 647, false);
			}
		} else if (cmd.equalsIgnoreCase("lottery")) {
	        if (World.lotteryRunning())
	                World.buyTicket(player);
	        else
	                player.sendMessage(Config.PREFIX + " There's no lottery running right now");
		} else if (cmd.equalsIgnoreCase("lotterypot")) {
	        World.getLotteryPot(player);     
		} 
		else if(cmd.equalsIgnoreCase("godspells") && player.isAdmin())
		{
			if(args.length != 1)
			{
				player.sendMessage("Invalid Syntax - Usage: godspells [boolean] eg. '::godspells true'");
				return;
			}
			try
			{
				Config.ALLOW_GODSPELLS = Boolean.parseBoolean(args[0]);
				for(Player p : World.getPlayers())
				{
					p.sendNotification(Config.PREFIX + "Godspells have been " + (Config.ALLOW_GODSPELLS ? "enabled" : "disabled"));
				}
			}
			catch(Exception e)
			{
				player.sendMessage("Invalid Syntax - Usage: godspells [boolean] eg. '::godspells true'");
				return;
			}
		}
		else if(cmd.equalsIgnoreCase("weakens") && player.isAdmin())
		{
			if(args.length != 1)
			{
				player.sendMessage("Invalid Syntax - Usage: weakens [boolean] eg. '::weakens true'");
				return;
			}
			try
			{
				Config.ALLOW_WEAKENS = Boolean.parseBoolean(args[0]);
				for(Player p : World.getPlayers())
				{
					p.sendNotification(Config.PREFIX + "Weaken spells have been " + (Config.ALLOW_WEAKENS ? "enabled" : "disabled"));
				}
			}
			catch(Exception e)
			{
				player.sendMessage("Invalid Syntax - Usage: weakens [boolean] eg. '::weakens true'");
				return;
			}
		} else
        /*
         * Change password
         */
        if (cmd.equalsIgnoreCase("changepassword")) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: CHANGEPASSWORD [new_password]");
				return;
			}
            
            // Check if a player already has a password change event.
            ArrayList events = World.getDelayedEventHandler().getEvents();
            Iterator<DelayedEvent> iterator = events.iterator();
            while (iterator.hasNext()) {
				DelayedEvent event = iterator.next();
                
                if(!(event instanceof ChangePasswordEvent)) continue;
                
                if(event.belongsTo(player)) {
                    player.sendMessage(Config.PREFIX + "You have already initiated a password change.");
                    player.sendMessage(Config.PREFIX + "Type ::confirmpassword [new_password] within 30 seconds to finish.");
                }
            }
			
			World.getDelayedEventHandler().add(new ChangePasswordEvent(player, args[0]));
            player.sendMessage(Config.PREFIX + "Password change initiated.");
            player.sendMessage(Config.PREFIX + "Type ::confirmpassword [new_password] within 30 seconds to finish.");
		}
		else
        /*
         * Change password
         */
		if (cmd.equalsIgnoreCase("confirmpassword")) 
		{
			if (args.length != 1) 
			{
				player.sendMessage(Config.PREFIX + "Invalid args. Syntax: CONFIRMPASSWORD [new_password]");
				return;
			}
            
            // Look for the existing password change event...
            ChangePasswordEvent originatingEvent = null;
            ArrayList events = World.getDelayedEventHandler().getEvents();
            Iterator<DelayedEvent> iterator = events.iterator();
            while (iterator.hasNext()) {
				DelayedEvent event = iterator.next();
                
                if(!(event instanceof ChangePasswordEvent)) continue;
                
                if(event.belongsTo(player)) {
                    originatingEvent = (ChangePasswordEvent)event;
                    break;
                }
            }
            
            if(originatingEvent == null){
                player.sendMessage(Config.PREFIX + "You have not initiated a password change.");
                player.sendMessage(Config.PREFIX + "Type ::changepassword [new_password] to change your password.");
                return;
            }
            
            originatingEvent.confirmPassword(args[0]);
		}
		else
        if (cmd.equalsIgnoreCase("startlottery") && (player.isMod() || player.isDev() || player.isEvent())) 
        {
            if (!World.lotteryRunning())
                if (args.length != 1)
                    player.sendMessage(Config.PREFIX + " Invalid args. Syntax: STARTLOTTERY [price]");
                else
                    try {
                        World.startLottery(Integer.parseInt(args[0]));
                    } catch (Exception e) {}       
        } 
        else 
        if (cmd.equalsIgnoreCase("stoplottery") && (player.isMod() || player.isDev() || player.isEvent())) 
        {
            if (World.lotteryRunning())
                World.stopLottery();
            else
                player.sendMessage(Config.PREFIX + " There's no lottery running right now");
        }
    }
	public static final ArrayList<String> statArray = new ArrayList<String>(){{
		add("attack"); add("defense"); add("strength"); add("hits"); add("ranged"); add("prayer"); add("magic"); add("cooking"); add("woodcut"); add("fletching"); add("fishing"); add("firemaking"); add("crafting"); add("smithing"); add("mining"); add("herblaw"); add("agility"); add("thieving"); add("runecrafting");
	}};	
}