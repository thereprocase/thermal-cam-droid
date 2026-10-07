#include "p2pro_protocol.hpp"
#include "p2pro_camera.hpp"
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

class FakeTransport : public p2pro::Transport {
public:
    std::vector<std::pair<std::uint16_t,std::vector<std::uint8_t>>> writes;
    std::uint8_t status = 0;
    bool short_reply = false;
    void write(std::uint16_t mailbox,const std::vector<std::uint8_t>& bytes) override {
        writes.emplace_back(mailbox,bytes);
    }
    std::vector<std::uint8_t> read(std::uint16_t mailbox,std::size_t length) override {
        if (short_reply) return {};
        if (mailbox==0x200) return {status};
        if (mailbox==0x1d10) return {0,128};
        return std::vector<std::uint8_t>(length);
    }
};

class PropertyTransport : public p2pro::Transport {
public:
    std::array<std::uint16_t, 6> values{7,275,280,90,75,0};
    int refused_index=-1;
    unsigned selected=0, property_writes=0;
    void write(std::uint16_t mailbox,const std::vector<std::uint8_t>& bytes) override {
        if(mailbox!=0x9d00) return;
        require(bytes.size()==8 && bytes[0]==0x14,"Baseline command header");
        selected=bytes[3];require(selected<6,"Baseline property index");
        if(bytes[1]==0xc5) {
            ++property_writes;
            if(int(selected)!=refused_index) values[selected]=std::uint16_t((bytes[6]<<8)|bytes[7]);
        } else require(bytes[1]==0x85,"Baseline read opcode");
    }
    std::vector<std::uint8_t> read(std::uint16_t mailbox,std::size_t length) override {
        if(mailbox==0x200) return {0};
        require(mailbox==0x1d10 && length==2,"Baseline reply mailbox");
        return {std::uint8_t(values[selected]>>8),std::uint8_t(values[selected])};
    }
};

void validate_baseline() {
    for(bool high : {false,true}) {
        PropertyTransport transport;transport.values[5]=high ? 0:1;
        const auto original=transport.values;
        p2pro::Camera camera(transport);
        const auto result=camera.establish_baseline(high);
        const std::array<std::uint16_t,6> expected{32,300,300,128,128,std::uint16_t(high ? 1:0)};
        require(result.original==original && result.configured==expected,"Baseline original/configured evidence");
        require(transport.property_writes==6,"Baseline changed every mismatching property");
        camera.establish_baseline(high);
        require(transport.property_writes==6,"Verified baseline avoids redundant writes");
        for(int index=0;index<6;++index) {
            PropertyTransport refused;refused.values=original;refused.refused_index=index;
            p2pro::Camera failing(refused);bool rejected=false;
            try { failing.establish_baseline(high); }
            catch(const std::runtime_error& error) {
                rejected=std::string(error.what())=="Startup property readback mismatch at index "+std::to_string(index);
            }
            require(rejected,"Each mismatched startup readback is rejected");
        }
    }
}

int main(int argc, char** argv) {
    using namespace p2pro;
    validate_baseline();
    if (argc != 2) throw std::invalid_argument("Captured fixture path required");
    require(standard_header(0x8405, 7, 16) == Header{5, 0x84, 7, 0, 0, 0, 0, 16}, "Serial header");
    require(long_header(0xc514, 5, 1) == Header{0x14, 0xc5, 0, 5, 0, 0, 0, 1}, "Gain header");
    require(long_parameters(0, 2) == Header{0, 0, 0, 0, 0, 0, 0, 2}, "Long read length");
    require(mailbox_state(4) == MailboxState::Error, "Ready bits must not hide errors");
    require(mailbox_state(3) == MailboxState::Busy, "Busy bits");
    std::ifstream input(argv[1], std::ios::binary);
    std::vector<std::uint8_t> data((std::istreambuf_iterator<char>(input)), {});
    auto raw = decode_plane(data.data(), data.size());
    require(raw.size() == 49152 && raw[0] == 19628 && raw[96 * 256 + 128] == 19052, "Captured sample bytes");
    require(std::abs(celsius(raw[96 * 256 + 128]) - 24.5375) < 1e-10, "Captured decode math");
    bool rejected = false;
    try { decode_plane(data.data(), data.size() - 1); }
    catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "Truncation rejection");
    FakeTransport transport;
    Camera camera(transport);
    require(camera.property(Property::Emissivity)==128,"TPD BE reply");
    require(transport.writes[0].second==std::vector<std::uint8_t>{0x14,0x85,0,3,0,0,0,0},"TPD read request");
    camera.set_property(Property::HighGain,1);
    require(transport.writes[2].second==std::vector<std::uint8_t>{0x14,0xc5,0,5,0,0,0,1},"TPD write request");
    camera.set_palette(3);
    require(transport.writes.back().second==std::vector<std::uint8_t>{3},"Palette payload");
    camera.nuc();
    require(transport.writes.back().second==std::vector<std::uint8_t>{0x0d,0xc1,0,0,0,0,0,0},"NUC candidate B-update request");
    require(camera.spi_read(0,512).size()==512,"Bounded SPI block read");
    require(transport.writes.back().second==std::vector<std::uint8_t>{1,0x82,0,0,1,0,1,0},"SPI incremented BE address");
    rejected=false;transport.status=4;
    try { camera.palette(); } catch (const std::runtime_error&) { rejected=true; }
    require(rejected,"Client propagates status error");
    rejected=false;transport.status=0;transport.short_reply=true;
    try { camera.palette(); } catch (const std::runtime_error&) { rejected=true; }
    require(rejected,"Client rejects short status reply");
    std::cout << "Native packet and captured-frame checks passed\n";
}
