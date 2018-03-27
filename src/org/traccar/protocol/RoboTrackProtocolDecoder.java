/*
 * Copyright 2018 Anton Tananaev (anton@traccar.org)
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

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.DeviceSession;
import org.traccar.helper.BitUtil;
import org.traccar.helper.Checksum;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.CellTower;
import org.traccar.model.Network;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class RoboTrackProtocolDecoder extends BaseProtocolDecoder {

    public RoboTrackProtocolDecoder(RoboTrackProtocol protocol) {
        super(protocol);
    }

    public static final int MSG_ID = 0x00;
    public static final int MSG_ACK = 0x80;
    public static final int MSG_GPS = 0x03;
    public static final int MSG_GSM = 0x04;
    public static final int MSG_IMAGE_START = 0x06;
    public static final int MSG_IMAGE_DATA = 0x07;
    public static final int MSG_IMAGE_END = 0x08;

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        int type = buf.readUnsignedByte();

        if (type == MSG_ID) {

            buf.skipBytes(16); // name

            String imei = buf.readBytes(15).toString(StandardCharsets.US_ASCII);

            if (getDeviceSession(channel, remoteAddress, imei) != null && channel != null) {
                ChannelBuffer response = ChannelBuffers.dynamicBuffer(ByteOrder.LITTLE_ENDIAN, 0);
                response.writeByte(MSG_ACK);
                response.writeByte(0x01); // success
                response.writeByte(Checksum.crc8(Checksum.CRC8_ROHC, response.toByteBuffer()));
                channel.write(response);
            }

        } else if (type == MSG_GPS || type == MSG_GSM) {

            DeviceSession deviceSession = getDeviceSession(channel, remoteAddress);
            if (deviceSession == null) {
                return null;
            }

            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());

            position.setDeviceTime(new Date(buf.readUnsignedInt() * 1000));

            if (type == MSG_GPS) {

                position.setValid(true);
                position.setFixTime(position.getDeviceTime());
                position.setLatitude(buf.readInt() * 0.000001);
                position.setLongitude(buf.readInt() * 0.000001);
                position.setSpeed(UnitsConverter.knotsFromKph(buf.readByte()));

            } else {

                getLastLocation(position, position.getDeviceTime());

                position.setNetwork(new Network(CellTower.from(
                        buf.readUnsignedShort(), buf.readUnsignedShort(),
                        buf.readUnsignedShort(), buf.readUnsignedShort())));

                buf.readUnsignedByte(); // reserved

            }

            int value = buf.readUnsignedByte();

            position.set(Position.KEY_SATELLITES, BitUtil.to(value, 4));
            position.set(Position.KEY_RSSI, BitUtil.between(value, 4, 7));
            position.set(Position.KEY_MOTION, BitUtil.check(value, 7));

            value = buf.readUnsignedByte();

            position.set(Position.KEY_CHARGE, BitUtil.check(value, 0));

            for (int i = 1; i <= 4; i++) {
                position.set(Position.PREFIX_IN + i, BitUtil.check(value, i));
            }

            position.set(Position.KEY_BATTERY_LEVEL, BitUtil.from(value, 5) * 100 / 7);
            position.set(Position.KEY_DEVICE_TEMP, buf.readByte());

            for (int i = 1; i <= 3; i++) {
                position.set(Position.PREFIX_ADC + i, buf.readUnsignedShort());
            }

            return position;

        }

        return null;
    }

}