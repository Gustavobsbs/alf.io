/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.manager.system.ConfigurationManager;
import alfio.model.*;
import alfio.model.modification.TicketReservationModification;
import alfio.model.modification.TicketReservationWithOptionalCodeModification;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.WaitingQueueRepository;
import alfio.util.WorkingDaysAdjusters;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@Log4j2
public class WaitingQueueManager {

    private final WaitingQueueRepository waitingQueueRepository;
    private final TicketRepository ticketRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final ConfigurationManager configurationManager;

    @Autowired
    public WaitingQueueManager(WaitingQueueRepository waitingQueueRepository,
                               TicketRepository ticketRepository,
                               TicketCategoryRepository ticketCategoryRepository,
                               ConfigurationManager configurationManager) {
        this.waitingQueueRepository = waitingQueueRepository;
        this.ticketRepository = ticketRepository;
        this.ticketCategoryRepository = ticketCategoryRepository;
        this.configurationManager = configurationManager;
    }

    Stream<Triple<WaitingQueueSubscription, TicketReservationWithOptionalCodeModification, ZonedDateTime>> distributeSeats(Event event) {
        int eventId = event.getId();
        int waitingPeople = waitingQueueRepository.countWaitingPeople(eventId);
        int waitingTickets = ticketRepository.countWaiting(eventId);
        if (waitingPeople == 0 && waitingTickets > 0) {
            ticketRepository.revertToFree(eventId);
        } else if (waitingPeople > 0 && waitingTickets > 0) {
            return distributeAvailableSeats(event, eventId, waitingPeople, waitingTickets);
        }
        return Stream.empty();
    }

    private Stream<Triple<WaitingQueueSubscription, TicketReservationWithOptionalCodeModification, ZonedDateTime>> distributeAvailableSeats(Event event, int eventId, int waitingPeople, int waitingTickets) {
        int availableSeats = Math.min(waitingPeople, waitingTickets);
        log.debug("processing {} subscribers from waiting queue", availableSeats);
        Iterator<Ticket> tickets = ticketRepository.selectWaitingTicketsForUpdate(eventId, availableSeats).iterator();
        Optional<TicketCategory> unboundedCategory = ticketCategoryRepository.findUnboundedOrderByExpirationDesc(eventId).stream().findFirst();
        int expirationTimeout = configurationManager.getIntConfigValue(Configuration.event(event), ConfigurationKeys.WAITING_QUEUE_RESERVATION_TIMEOUT, 4);
        ZonedDateTime expiration = ZonedDateTime.now(event.getZoneId()).plusHours(expirationTimeout).with(WorkingDaysAdjusters.defaultWorkingDays());

        return waitingQueueRepository.loadWaiting(eventId, availableSeats).stream()
            .map(wq -> Pair.of(wq, tickets.next()))
            .map(pair -> {
                TicketReservationModification ticketReservation = new TicketReservationModification();
                ticketReservation.setAmount(1);
                Integer categoryId = Optional.ofNullable(pair.getValue().getCategoryId()).orElseGet(() -> unboundedCategory.orElseThrow(RuntimeException::new).getId());
                ticketReservation.setTicketCategoryId(categoryId);
                return Pair.of(pair.getLeft(), new TicketReservationWithOptionalCodeModification(ticketReservation, Optional.<SpecialPrice>empty()));
            })
            .map(pair -> Triple.of(pair.getKey(), pair.getValue(), expiration));
    }

    public void fireReservationConfirmed(String reservationId) {
        updateStatus(reservationId, WaitingQueueSubscription.Status.ACQUIRED.toString());
    }

    public void fireReservationExpired(String reservationId) {
        updateStatus(reservationId, WaitingQueueSubscription.Status.EXPIRED.toString());
    }

    public void cleanExpiredReservations(List<String> reservationIds) {
        waitingQueueRepository.bulkUpdateExpiredReservations(reservationIds);
    }

    private void updateStatus(String reservationId, String status) {
        waitingQueueRepository.updateStatusByReservationId(reservationId, status);
    }
}
