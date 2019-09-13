/*
 * Copyright 2012 - 2019 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.BitUtil;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;

public class SinotrackProtocolDecoder extends BaseProtocolDecoder {

    public SinotrackProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private void processStatus(Position position, long status) {

        if (!BitUtil.check(status, 0)) {
            position.set(Position.KEY_ALARM, Position.ALARM_VIBRATION);
        } else if (!BitUtil.check(status, 1) || !BitUtil.check(status, 18)) {
            position.set(Position.KEY_ALARM, Position.ALARM_SOS);
        } else if (!BitUtil.check(status, 2)) {
            position.set(Position.KEY_ALARM, Position.ALARM_OVERSPEED);
        } else if (!BitUtil.check(status, 19)) {
            position.set(Position.KEY_ALARM, Position.ALARM_POWER_CUT);
        }

        position.set(Position.KEY_IGNITION, BitUtil.check(status, 10));
        position.set(Position.KEY_STATUS, status);

    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("*")
            .expression("..,")                   // manufacturer
            .number("(d+)?,")                    // imei
            .groupBegin()
            .text("V4,")
            .expression("(.*),")                 // response
            .or()
            .expression("(V[^,]*),")
            .groupEnd()
            .number("(?:(dd)(dd)(dd))?,")        // time (hhmmss)
            .groupBegin()
            .expression("([ABV])?,")             // validity
            .or()
            .number("(d+),")                     // coding scheme
            .groupEnd()
            .groupBegin()
            .number("-(d+)-(d+.d+),")            // latitude
            .or()
            .number("(d+)(dd.d+),")              // latitude
            .groupEnd()
            .expression("([NS]),")
            .groupBegin()
            .number("-(d+)-(d+.d+),")            // longitude
            .or()
            .number("(d+)(dd.d+),")              // longitude
            .groupEnd()
            .expression("([EW]),")
            .number("(d+.?d*),")                 // speed
            .number("(d+.?d*)?,")                // course
            .number("(?:d+,)?")                  // battery
            .number("(?:(dd)(dd)(dd))?")         // date (ddmmyy)
            .groupBegin()
            .expression(",[^,]*,")
            .expression("[^,]*,")
            .expression("[^,]*")                 // sim info
            .groupEnd("?")
            .groupBegin()
            .number(",(x{8})")
            .groupBegin()
            .number(",(d+),")                    // mcc
            .number("(d+),")                   // mnc
            .number("(d+),")                  // lac
            .number("(d+)")                   // cid
            .or()
            .groupEnd()
            .or()
            .groupEnd()
            .text("#")
            .compile();

    private void sendResponse(Channel channel, SocketAddress remoteAddress, String id) {
        if (channel != null && id != null) {
            DateFormat dateFormat = new SimpleDateFormat("HHmmss");
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            String response = String.format("*HQ,%s,D4,%s#", id, dateFormat.format(new Date()));
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    private Position decodeText(String sentence, Channel channel, SocketAddress remoteAddress) {

        Parser parser = new Parser(PATTERN, sentence);
        if (!parser.matches()) {
            return null;
        }

        String id = parser.next();
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, id);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (parser.hasNext()) {
            position.set(Position.KEY_RESULT, parser.next());
        }

        if (parser.hasNext() && parser.next().equals("V1")) {
            sendResponse(channel, remoteAddress, id);
        }

        DateBuilder dateBuilder = new DateBuilder();
        if (parser.hasNext(3)) {
            dateBuilder.setTime(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
        }

        if (parser.hasNext()) {
            position.setValid(parser.next().equals("A"));
        }
        if (parser.hasNext()) {
            parser.nextInt(); // coding scheme
            position.setValid(true);
        }

        if (parser.hasNext(2)) {
            position.setLatitude(-parser.nextCoordinate());
        }
        if (parser.hasNext(2)) {
            position.setLatitude(parser.nextCoordinate());
        }

        if (parser.hasNext(2)) {
            position.setLongitude(-parser.nextCoordinate());
        }
        if (parser.hasNext(2)) {
            position.setLongitude(parser.nextCoordinate());
        }

        position.setSpeed(parser.nextDouble(0));
        position.setCourse(parser.nextDouble(0));

        if (parser.hasNext(3)) {
            dateBuilder.setDateReverse(parser.nextInt(0), parser.nextInt(0), parser.nextInt(0));
            position.setTime(dateBuilder.getDate());
        } else {
            position.setTime(new Date());
        }

        if (parser.hasNext()) {
            processStatus(position, parser.nextLong(16, 0));
        }

        if (parser.hasNext(4)) {
            int mcc = parser.nextInt();
            int mnc = parser.nextInt();
            int lac = parser.nextInt();
            int cid = parser.nextInt();
            Network network = new Network();
            network.addCellTower(CellTower.from(mcc, mnc, lac, cid));
            position.setNetwork(network);
        }

        return position;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;
        String marker = buf.toString(0, 1, StandardCharsets.US_ASCII);

        switch (marker) {
            case "*":
                String sentence = buf.toString(StandardCharsets.US_ASCII).trim();
                return decodeText(sentence, channel, remoteAddress);
            case "$":
            case "X":
            default:
                return null;
        }
    }

}
