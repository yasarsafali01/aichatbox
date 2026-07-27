package com.yasarsafali.rag_backend.service.quote;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.yasarsafali.rag_backend.dto.quote.Brans;
import com.yasarsafali.rag_backend.dto.quote.QuoteField;
import com.yasarsafali.rag_backend.dto.quote.QuoteResult;
import com.yasarsafali.rag_backend.dto.quote.QuoteSession;
import com.yasarsafali.rag_backend.dto.quote.QuoteStepResponse;

@Service
public class QuoteService {

    private final Map<String, QuoteSession> sessions = new ConcurrentHashMap<>();
    private final QuoteFlowRegistry flowRegistry;
    private final PricingEngine pricingEngine;

    public QuoteService(QuoteFlowRegistry flowRegistry, PricingEngine pricingEngine) {
        this.flowRegistry = flowRegistry;
        this.pricingEngine = pricingEngine;
    }

    public QuoteStepResponse start(Brans brans) {
        String id = UUID.randomUUID().toString();
        QuoteSession session = new QuoteSession(id, brans);
        sessions.put(id, session);
        return toStepResponse(session, null);
    }

    public QuoteStepResponse answer(String sessionId, String answer) {
        QuoteSession session = sessions.get(sessionId);
        if (session == null) {
            return errorResponse("Oturum bulunamadı. Lütfen teklif akışını yeniden başlatın.");
        }
        if (session.isCompleted()) {
            return toStepResponse(session, null);
        }

        List<QuoteField> fields = flowRegistry.fieldsFor(session.getBrans());
        QuoteField currentField = fields.get(session.getStepIndex());

        if (!currentField.isValid(answer)) {
            return toStepResponse(session, currentField.validationHint());
        }

        session.getAnswers().put(currentField.key(), answer.trim());
        session.advance();

        return toStepResponse(session, null);
    }

    public QuoteStepResponse prefill(String sessionId, Map<String, String> fields) {
        QuoteSession session = sessions.get(sessionId);
        if (session == null) {
            return errorResponse("Oturum bulunamadı. Lütfen teklif akışını yeniden başlatın.");
        }
        if (fields == null) {
            return toStepResponse(session, null);
        }

        List<QuoteField> flowFields = flowRegistry.fieldsFor(session.getBrans());
        while (session.getStepIndex() < flowFields.size()) {
            QuoteField field = flowFields.get(session.getStepIndex());
            String value = fields.get(field.key());
            if (value == null || !field.isValid(value)) break;
            session.getAnswers().put(field.key(), value.trim());
            session.advance();
        }

        return toStepResponse(session, null);
    }

    private QuoteStepResponse toStepResponse(QuoteSession session, String error) {
        List<QuoteField> fields = flowRegistry.fieldsFor(session.getBrans());

        if (session.getStepIndex() >= fields.size()) {
            session.setCompleted(true);
            QuoteResult quote = pricingEngine.calculate(session.getBrans(), session.getAnswers());
            return new QuoteStepResponse(session.getId(), session.getBrans(), true,
                    null, null, fields.size(), fields.size(), null, quote);
        }

        QuoteField field = fields.get(session.getStepIndex());
        return new QuoteStepResponse(session.getId(), session.getBrans(), false,
                field.key(), field.question(), session.getStepIndex(), fields.size(), error, null);
    }

    private QuoteStepResponse errorResponse(String message) {
        return new QuoteStepResponse(null, null, false, null, null, 0, 0, message, null);
    }
}
